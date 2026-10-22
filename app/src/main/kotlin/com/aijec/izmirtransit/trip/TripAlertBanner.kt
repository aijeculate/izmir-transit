package com.aijec.izmirtransit.trip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.data.trip.TripStatus
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.calmSurface
import com.aijec.izmirtransit.core.ui.rememberReducedMotion

/**
 * While a tracked trip reaches its stop, a glass banner drops in at the top of the app on a
 * spring (the notification says the same when the app isn't open). Its rim glows softly
 * while it waits for the traveller to act. Tap to hide it until the next alert.
 */
@Composable
fun TripAlertBanner(status: TripStatus?, modifier: Modifier = Modifier) {
    val alert = when (status) {
        is TripStatus.GetOffNext -> Alert(status.ride.line, stringResource(R.string.trip_banner_next), status.ride.alight.name)
        is TripStatus.GetOffNow -> Alert(
            status.ride.line,
            stringResource(R.string.trip_get_off_now, status.ride.alight.name),
            status.then?.let { stringResource(R.string.trip_change_to, it.line.displayCode, it.headsign) }.orEmpty(),
        )
        else -> null
    }
    var dismissed by remember { mutableStateOf<String?>(null) }
    // Keep the last alert while the banner slides away.
    var shown by remember { mutableStateOf<Alert?>(null) }
    if (alert != null) shown = alert
    val visible = alert != null && dismissed != alert.title
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) fadeIn() else slideInVertically(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) { -it * 13 / 10 } + fadeIn(tween(200)),
        exit = if (reduced) fadeOut() else slideOutVertically(tween(260)) { -it * 13 / 10 } + fadeOut(tween(200)),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val glow by rememberInfiniteTransition(label = "trip-glow").animateFloat(
            initialValue = 0.16f,
            targetValue = if (reduced) 0.16f else 0.5f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "trip-glow",
        )
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .calmSurface(Calm.PanelShape, overMap = true)
                .border(1.dp, Calm.colors.accent.copy(alpha = glow), Calm.PanelShape)
                .clickable { dismissed = current.title }
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineBadge(current.line, strong = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, style = MaterialTheme.typography.titleMedium, color = Calm.colors.text)
                if (current.body.isNotEmpty()) {
                    Text(current.body, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary)
                }
            }
        }
    }
}

private data class Alert(val line: com.aijec.izmirtransit.core.model.Line, val title: String, val body: String)
