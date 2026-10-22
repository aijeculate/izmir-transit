package com.aijec.izmirtransit.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.IzmirTheme
import com.aijec.izmirtransit.core.ui.ModeIcon
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.SectionLabel
import com.aijec.izmirtransit.core.ui.stopKindLabel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which stop each placed widget shows, by app-widget id; none means the first favourite. */
object WidgetStops {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_stops", Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): String? = prefs(context).getString(key(appWidgetId), null)

    fun set(context: Context, appWidgetId: Int, stopCode: String?) {
        prefs(context).edit().apply { if (stopCode == null) remove(key(appWidgetId)) else putString(key(appWidgetId), stopCode) }.apply()
    }

    private fun key(id: Int) = "stop_$id"
}

/**
 * Opens when a widget is placed (before Android 12) or reconfigured (long-press → settings):
 * the user picks one of their favourite stops or stations, or "first favourite".
 */
@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {
    @Inject lateinit var favourites: FavouritesRepository
    @Inject lateinit var stops: StopRepository
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced (or unchanged).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val appSettings by produceState<com.aijec.izmirtransit.core.data.repository.AppSettings?>(null) { value = settings.settings.first() }
            val current = appSettings ?: return@setContent
            IzmirTheme(themeMode = current.themeMode, accentTheme = current.accent, style = current.style) {
                val favouriteStops by produceState<List<Stop>?>(null) {
                    value = favourites.favourites.first().stopCodes.mapNotNull { stops.stop(it) }
                }
                val scope = rememberCoroutineScope()
                ConfigScreen(
                    stops = favouriteStops,
                    selected = WidgetStops.get(this, appWidgetId),
                    onChoose = { code ->
                        WidgetStops.set(this, appWidgetId, code)
                        scope.launch {
                            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                            FavouriteStopWidget().update(this@WidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(stops: List<Stop>?, selected: String?, onChoose: (String?) -> Unit) {
    val colors = Calm.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.widget_config_title), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Spacer(Modifier.padding(4.dp))
        Text(stringResource(R.string.widget_config_hint), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        SectionLabel(stringResource(R.string.widget_config_section))
        Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
            LazyColumn {
                item("auto") {
                    Choice(stringResource(R.string.widget_config_first), stringResource(R.string.widget_config_first_hint), selected == null, mode = null) { onChoose(null) }
                }
                items(stops.orEmpty(), key = { it.code }) { stop ->
                    Hairline()
                    Choice(stop.name, stopKindLabel(stop), selected == stop.code, mode = stop) { onChoose(stop.code) }
                }
            }
        }
        if (stops != null && stops.isEmpty()) {
            Text(
                stringResource(R.string.widget_no_favourite),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, mode: Stop?, onClick: () -> Unit) {
    val colors = Calm.colors
    CalmRow(onClick = onClick) {
        if (mode != null) {
            ModeIcon(mode.mode, size = 30.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent)
    }
}
