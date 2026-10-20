package com.aijec.izmirtransit.alerts

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aijec.izmirtransit.MainActivity
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.data.alerts.AlertRequest
import com.aijec.izmirtransit.core.data.alerts.ArrivalAlerts
import com.aijec.izmirtransit.core.data.alerts.ArrivalWatch
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekLiveDataSource
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Follows one bus until it is within the chosen number of stops, then alerts. Runs as a
 * foreground service (with an ongoing notification and a Stop action) so it keeps working
 * when the user locks the phone, and gives up after an hour or when the bus leaves the feed.
 * Polls through the shared RequestPacer like every other live call.
 */
@AndroidEntryPoint
class ArrivalAlertService : Service() {
    @Inject lateinit var source: IztekLiveDataSource
    @Inject lateinit var alerts: AndroidArrivalAlerts

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val request = intent?.toRequest()
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            trackingNotification(request, stopsRemaining = null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        job?.cancel()
        job = scope.launch { watch(request) }
        return START_NOT_STICKY
    }

    private suspend fun watch(request: AlertRequest) {
        val watch = ArrivalWatch(request.busId, request.alertAtStops)
        val deadline = SystemClock.elapsedRealtime() + MAX_TRACKING_MILLIS
        val manager = NotificationManagerCompat.from(this)
        while (SystemClock.elapsedRealtime() < deadline) {
            when (val step = watch.onResult(source.lineApproachingBuses(request.lineCode, request.stopCode))) {
                is ArrivalWatch.Step.Tracking -> notifySafely(manager, ONGOING_ID, trackingNotification(request, step.stopsRemaining))
                is ArrivalWatch.Step.Alert -> {
                    alert(request, getString(R.string.alert_near, request.lineCode, step.stopsRemaining, request.stopName))
                    finish()
                    return
                }
                is ArrivalWatch.Step.Lost -> {
                    alert(request, getString(if (step.likelyArrived) R.string.alert_arrived else R.string.alert_lost, request.lineCode, request.stopName))
                    finish()
                    return
                }
                ArrivalWatch.Step.Waiting -> Unit
            }
            delay(POLL_MILLIS)
        }
        alert(request, getString(R.string.alert_timeout, request.lineCode))
        finish()
    }

    private fun alert(request: AlertRequest, text: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.alert_title, request.lineCode))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openStopIntent(request))
            .build()
        notifySafely(NotificationManagerCompat.from(this), ALERT_ID, notification)
    }

    private fun trackingNotification(request: AlertRequest, stopsRemaining: Int?): Notification =
        NotificationCompat.Builder(this, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.alert_tracking_title, request.lineCode, request.stopName))
            .setContentText(
                if (stopsRemaining == null) getString(R.string.alert_tracking_waiting, request.alertAtStops)
                else resources.getQuantityString(R.plurals.alert_tracking_stops, stopsRemaining, stopsRemaining, request.alertAtStops),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openStopIntent(request))
            .addAction(0, getString(R.string.alert_stop), stopPendingIntent())
            .build()

    /** Notifications may be disabled by the user; tracking still ends cleanly. */
    private fun notifySafely(manager: NotificationManagerCompat, id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(id, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked mid-way.
            }
        }
    }

    private fun openStopIntent(request: AlertRequest): PendingIntent =
        PendingIntent.getActivity(
            this,
            request.stopCode.hashCode(),
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_STOP, request.stopCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun finish() {
        alerts.markFinished()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        job?.cancel()
    }

    override fun onDestroy() {
        alerts.markFinished()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.aijec.izmirtransit.alerts.START"
        private const val ACTION_STOP = "com.aijec.izmirtransit.alerts.STOP"
        private const val EXTRA_BUS = "bus"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_AT_STOPS = "atStops"
        private const val TRACKING_CHANNEL = "tracking"
        private const val ALERT_CHANNEL = "arrival_alerts"
        private const val ONGOING_ID = 1001
        private const val ALERT_ID = 1002
        private const val POLL_MILLIS = 20_000L
        private const val MAX_TRACKING_MILLIS = 60 * 60_000L

        fun startIntent(context: Context, request: AlertRequest): Intent =
            Intent(context, ArrivalAlertService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BUS, request.busId)
                .putExtra(EXTRA_LINE, request.lineCode)
                .putExtra(EXTRA_STOP, request.stopCode)
                .putExtra(EXTRA_STOP_NAME, request.stopName)
                .putExtra(EXTRA_AT_STOPS, request.alertAtStops)

        fun stopIntent(context: Context): Intent = Intent(context, ArrivalAlertService::class.java).setAction(ACTION_STOP)

        private fun Intent.toRequest(): AlertRequest? {
            val line = getStringExtra(EXTRA_LINE) ?: return null
            val stop = getStringExtra(EXTRA_STOP) ?: return null
            return AlertRequest(
                busId = getIntExtra(EXTRA_BUS, -1).takeIf { it >= 0 } ?: return null,
                lineCode = line,
                stopCode = stop,
                stopName = getStringExtra(EXTRA_STOP_NAME).orEmpty(),
                alertAtStops = getIntExtra(EXTRA_AT_STOPS, 2),
            )
        }

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(TRACKING_CHANNEL, context.getString(R.string.alert_channel_tracking), NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, context.getString(R.string.alert_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}

/** App-side [ArrivalAlerts]: starts the service and exposes which alert is active. */
@Singleton
class AndroidArrivalAlerts @Inject constructor(@ApplicationContext private val context: Context) : ArrivalAlerts {
    private val _active = MutableStateFlow<AlertRequest?>(null)
    override val active: StateFlow<AlertRequest?> = _active.asStateFlow()

    override fun start(request: AlertRequest) {
        _active.value = request
        ContextCompat.startForegroundService(context, ArrivalAlertService.startIntent(context, request))
    }

    override fun stop() {
        context.startService(ArrivalAlertService.stopIntent(context))
        _active.value = null
    }

    fun markFinished() {
        _active.value = null
    }
}
