package com.aijec.izmirtransit.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aijec.izmirtransit.MainActivity
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget: the first favourite bus stop and its next three arrivals. Widgets can't
 * poll every 15 s like the app, so it refreshes every 15 minutes (the Android minimum)
 * and whenever the refresh button is tapped; the "updated" time says how fresh it is.
 */
class FavouriteStopWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun favourites(): FavouritesRepository
        fun stops(): StopRepository
        fun live(): LiveBusRepository
        fun clock(): Clock
    }

    private sealed interface Content {
        data object NoFavourite : Content
        data class Arrivals(val stop: Stop, val buses: List<ApproachingBus>?, val updatedAt: String) : Content
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, Dependencies::class.java)
        // Bus stops only: a station board would go stale between the widget's 15-minute refreshes.
        val stopCode = deps.favourites().favourites.first().stopCodes.firstOrNull(::isBusCode)
        val stop = stopCode?.let { deps.stops().stop(it) }
        val content = if (stop == null) {
            Content.NoFavourite
        } else {
            val buses = (deps.live().stopArrivalsOnce(stop.code) as? FetchResult.Success)?.value
            val time = TimetableRepository.localNow(deps.clock()).format(DateTimeFormatter.ofPattern("HH:mm"))
            Content.Arrivals(stop, buses, time)
        }
        provideContent { Widget(context, content) }
    }

    @androidx.compose.runtime.Composable
    private fun Widget(context: Context, content: Content) {
        val openApp = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (content is Content.Arrivals) openApp.putExtra(MainActivity.EXTRA_OPEN_STOP, content.stop.code)
        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(22.dp)
                .background(Panel)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(actionStartActivity(openApp)),
        ) {
            when (content) {
                Content.NoFavourite -> {
                    Text(context.getString(R.string.widget_title), style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.height(6.dp))
                    Text(context.getString(R.string.widget_no_favourite), style = TextStyle(color = TextSecondary, fontSize = 13.sp))
                }
                is Content.Arrivals -> {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            content.stop.name,
                            maxLines = 1,
                            style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Image(
                            ImageProvider(R.drawable.ic_widget_refresh),
                            contentDescription = context.getString(R.string.widget_refresh),
                            modifier = GlanceModifier.size(28.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    val buses = content.buses
                    when {
                        buses == null -> Text(context.getString(R.string.widget_unavailable), style = TextStyle(color = TextSecondary, fontSize = 13.sp))
                        buses.isEmpty() -> Text(context.getString(R.string.widget_none), style = TextStyle(color = TextSecondary, fontSize = 13.sp))
                        else -> buses.take(3).forEach { bus -> ArrivalRow(context, bus) }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Text(context.getString(R.string.widget_updated, content.updatedAt), style = TextStyle(color = TextSecondary, fontSize = 11.sp))
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ArrivalRow(context: Context, bus: ApproachingBus) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.cornerRadius(10.dp).background(AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(bus.lineCode, style = TextStyle(color = OnAccentSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                context.resources.getQuantityString(R.plurals.widget_stops, bus.stopsRemaining, bus.stopsRemaining),
                style = TextStyle(color = TextSecondary, fontSize = 12.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                if (bus.eta.minutes <= 0) context.getString(R.string.widget_now) else context.getString(R.string.widget_minutes, bus.eta.minutes),
                style = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    private companion object {
        // The app's calm palette, light and dark (see core:ui Theme.kt).
        val Panel = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF192028))
        val TextPrimary = ColorProvider(day = Color(0xFF1C2430), night = Color(0xFFE8EDF3))
        val TextSecondary = ColorProvider(day = Color(0xFF6B7685), night = Color(0xFF97A2B0))
        val Accent = ColorProvider(day = Color(0xFF1F6FD1), night = Color(0xFF7DB0FF))
        val AccentSoft = ColorProvider(day = Color(0xFFE6F0FB), night = Color(0xFF1C2F47))
        val OnAccentSoft = ColorProvider(day = Color(0xFF0C447C), night = Color(0xFFCFE2FF))
    }
}

/** The refresh button on the widget. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        FavouriteStopWidget().update(context, glanceId)
    }
}

class FavouriteStopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouriteStopWidget()

    /** First widget placed: start the periodic refresh. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }

    /** Last widget removed: stop it, so the app does no background work for nothing. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WidgetRefreshWorker.UNIQUE_NAME)
    }
}

/** Refreshes widgets every 15 minutes while at least one is on the home screen. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        FavouriteStopWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "widget-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
