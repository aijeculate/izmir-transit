package com.aijec.izmirtransit.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aijec.izmirtransit.MainActivity
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.data.trip.ActiveTrip
import com.aijec.izmirtransit.core.data.trip.TripProgress
import com.aijec.izmirtransit.core.data.trip.TripStatus
import com.aijec.izmirtransit.core.data.trip.TripTracking
import com.aijec.izmirtransit.core.location.LocationProvider
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the trip being followed; the service reads it, the planner starts and stops it. */
@Singleton
class AndroidTripTracking @Inject constructor(@ApplicationContext private val context: Context) : TripTracking {
    private val _active = MutableStateFlow<ActiveTrip?>(null)
    override val active: StateFlow<ActiveTrip?> = _active.asStateFlow()
    internal val _status = MutableStateFlow<TripStatus?>(null)
    override val status: StateFlow<TripStatus?> = _status.asStateFlow()

    override fun start(trip: ActiveTrip) {
        _active.value = trip
        _status.value = null
        ContextCompat.startForegroundService(context, Intent(context, TripTrackingService::class.java))
    }

    override fun stop() {
        context.startService(TripTrackingService.stopIntent(context))
    }

    internal fun markFinished() {
        _active.value = null
        _status.value = null
    }
}

/**
 * Follows a planned trip with GPS while the phone is in a pocket: an ongoing notification
 * says what to do now ("Walk to Konak", "3 stops to Halkapınar"), and a heads-up alert says
 * when to get off and where to change. Ends on arrival, an hour after the planned arrival,
 * or from the notification's Stop button.
 */
@AndroidEntryPoint
class TripTrackingService : Service() {
    @Inject lateinit var tracking: AndroidTripTracking
    @Inject lateinit var location: LocationProvider
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trip = tracking.active.value
        if (intent?.action == ACTION_STOP || trip == null) {
            finish()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoing(getString(R.string.trip_starting), trip),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        job?.cancel()
        job = scope.launch { follow(trip) }
        return START_NOT_STICKY
    }

    private suspend fun follow(trip: ActiveTrip) {
        val progress = TripProgress(trip.journey)
        var lastFix: LocationProvider.Fix? = null
        var lastAlertKey: String? = null
        // Fixes arrive every few seconds; without any (underground) the timetable carries on.
        val updates = scope.launch { location.updates().collect { lastFix = it } }
        try {
            while (true) {
                val minute = minuteNow(trip)
                val fix = lastFix
                val status = progress.update(fix?.location, fix?.accuracyMeters, minute)
                tracking._status.value = status
                notify(ONGOING_ID, ongoing(describe(status, trip), trip))
                // A heads-up alert once per moment that needs action.
                val alert = alertFor(status)
                if (alert != null && alert.first != lastAlertKey) {
                    lastAlertKey = alert.first
                    notify(ALERT_ID, headsUp(alert.second, trip))
                }
                if (status == TripStatus.Arrived || minute > trip.journey.arrivalMinute + GIVE_UP_AFTER_MINUTES) break
                delay(TICK_MILLIS)
            }
        } finally {
            updates.cancel()
        }
        finish()
    }

    /** Minutes on the journey's clock; a trip planned past midnight runs on (1440+). */
    private fun minuteNow(trip: ActiveTrip): Int {
        val minute = TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)).minutesAfterMidnight
        return if (trip.journey.departureMinute - minute > 12 * 60) minute + 24 * 60 else minute
    }

    private fun describe(status: TripStatus, trip: ActiveTrip): String = when (status) {
        is TripStatus.WalkTo -> getString(R.string.trip_walk_to, status.stop.name, status.meters) +
            (status.next?.let { " · " + getString(R.string.trip_then_take, it.line.displayCode, it.headsign, clock(it.startMinute)) } ?: "")
        is TripStatus.WaitFor -> getString(R.string.trip_wait_for, status.ride.line.displayCode, status.ride.headsign, clock(status.ride.startMinute))
        is TripStatus.OnBoard -> resources.getQuantityString(R.plurals.trip_stops_left, status.stopsLeft, status.stopsLeft, status.ride.alight.name) +
            " · " + getString(R.string.trip_next_stop, status.nextStop.name)
        is TripStatus.GetOffNext -> getString(R.string.trip_get_off_next, status.ride.alight.name)
        is TripStatus.GetOffNow -> getString(R.string.trip_get_off_now, status.ride.alight.name) +
            (status.then?.let { " " + getString(R.string.trip_change_to, it.line.displayCode, it.headsign) } ?: "")
        is TripStatus.WalkToDestination -> getString(R.string.trip_walk_destination, trip.destinationName.ifBlank { getString(R.string.trip_destination) }, status.meters)
        TripStatus.Arrived -> getString(R.string.trip_arrived)
    }

    private fun alertFor(status: TripStatus): Pair<String, String>? = when (status) {
        is TripStatus.GetOffNext -> "next-${status.ride.alight.code}" to getString(R.string.trip_get_off_next, status.ride.alight.name)
        is TripStatus.GetOffNow -> "now-${status.ride.alight.code}" to (
            getString(R.string.trip_get_off_now, status.ride.alight.name) +
                (status.then?.let { " " + getString(R.string.trip_change_to, it.line.displayCode, it.headsign) } ?: "")
            )
        TripStatus.Arrived -> "arrived" to getString(R.string.trip_arrived)
        else -> null
    }

    private fun clock(minute: Int) = TimetableRepository.formatClock(minute)

    private fun ongoing(text: String, trip: ActiveTrip): Notification =
        NotificationCompat.Builder(this, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.trip_title, trip.destinationName.ifBlank { getString(R.string.trip_destination) }))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.trip_stop), PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun headsUp(text: String, trip: ActiveTrip): Notification =
        NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.trip_title, trip.destinationName.ifBlank { getString(R.string.trip_destination) }))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Notifications may be off; tracking still runs and ends cleanly. */
    private fun notify(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-way.
        }
    }

    private fun finish() {
        tracking.markFinished()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        job?.cancel()
    }

    override fun onDestroy() {
        tracking.markFinished()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.aijec.izmirtransit.trip.STOP"
        private const val TRACKING_CHANNEL = "trip_tracking"
        private const val ALERT_CHANNEL = "trip_alerts"
        private const val ONGOING_ID = 2001
        private const val ALERT_ID = 2002
        private const val TICK_MILLIS = 5_000L
        private const val GIVE_UP_AFTER_MINUTES = 60

        fun stopIntent(context: Context): Intent = Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP)

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(TRACKING_CHANNEL, context.getString(R.string.trip_channel_tracking), NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, context.getString(R.string.trip_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
