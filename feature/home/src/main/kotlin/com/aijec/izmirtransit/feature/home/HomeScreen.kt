package com.aijec.izmirtransit.feature.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.EtaText
import com.aijec.izmirtransit.core.ui.FloatingBarClearance
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.PillButton
import com.aijec.izmirtransit.core.ui.SectionLabel
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow
import com.aijec.izmirtransit.core.ui.tabular
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(
    onOpenStop: (String) -> Unit,
    onOpenLine: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val arrivals by viewModel.arrivals.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()

    // Refresh the location each time the screen comes back into view.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshNearby() }

    HomeScreen(
        favourites = favourites,
        arrivals = arrivals,
        nearby = nearby,
        onRefreshNearby = viewModel::refreshNearby,
        onOpenStop = onOpenStop,
        onOpenLine = onOpenLine,
    )
}

@Composable
fun HomeScreen(
    favourites: FavouritesState,
    arrivals: Map<String, LiveSnapshot<List<ApproachingBus>>>,
    nearby: NearbyState,
    onRefreshNearby: () -> Unit,
    onOpenStop: (String) -> Unit,
    onOpenLine: (String) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Calm.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        // The list scrolls behind the floating tab bar and the gesture bar.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 24.dp + FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") { Greeting() }

        item("fav-label") { SectionLabel(stringResource(R.string.home_favourites)) }
        if (favourites.loaded && favourites.stops.isEmpty() && favourites.lines.isEmpty()) {
            item("fav-empty") { EmptyFavourites() }
        }
        items(favourites.stops, key = { "fav-stop-${it.stop.code}" }) { favourite ->
            FavouriteStopPanel(favourite, arrivals[favourite.stop.code], onClick = { onOpenStop(favourite.stop.code) })
        }
        if (favourites.lines.isNotEmpty()) {
            item("fav-lines") { FavouriteLinesPanel(favourites.lines, onOpenLine) }
        }

        item("nearby-label") {
            SectionLabel(stringResource(R.string.home_nearby)) {
                if (nearby is NearbyState.Found || nearby is NearbyState.Unavailable) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onRefreshNearby),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.home_nearby_refresh),
                            tint = Calm.colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        nearbySection(nearby, onRefreshNearby, onOpenStop)
    }
}

/** Date line and a time-of-day greeting in İzmir time. */
@Composable
private fun Greeting() {
    val now by rememberNow()
    val local = LocalDateTime.ofInstant(now, ZoneId.of("Europe/Istanbul"))
    val locale = LocalConfiguration.current.locales[0]
    val greeting = stringResource(
        when (local.hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_day
            in 18..22 -> R.string.home_greeting_evening
            else -> R.string.home_greeting_night
        },
    )
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(
            local.format(DateTimeFormatter.ofPattern("EEEE · HH:mm", locale)),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = Calm.colors.textSecondary,
        )
        Text(greeting, style = MaterialTheme.typography.displaySmall, color = Calm.colors.text)
    }
}

@Composable
private fun EmptyFavourites() {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Calm.colors.heart, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_favourites_empty), style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary)
        }
    }
}

@Composable
private fun FavouriteStopPanel(favourite: FavouriteStop, arrivals: LiveSnapshot<List<ApproachingBus>>?, onClick: () -> Unit) {
    val snapshot = arrivals ?: LoadingArrivals
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        CalmRow(onClick = onClick, padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(favourite.stop.name, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    linesText(favourite.lineCodes, favourite.stop.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val buses = snapshot.data.orEmpty()
            when {
                snapshot.status == LiveStatus.LOADING -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.accentSoft)
                }
                snapshot.status == LiveStatus.UNAVAILABLE -> Text(liveErrorText(snapshot.lastError), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                buses.isEmpty() -> Text(stringResource(R.string.home_arrivals_none), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                else -> buses.take(3).forEach { bus ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(bus.lineCode)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            pluralStringResource(R.plurals.home_stops_away, bus.stopsRemaining, bus.stopsRemaining),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        EtaText(bus.eta.minutes, uncertain = bus.eta.basis == EtaBasis.UNRELIABLE)
                    }
                }
            }
            if (snapshot.status == LiveStatus.STALE) {
                Text(liveErrorText(snapshot.lastError), style = MaterialTheme.typography.bodySmall, color = colors.onWarningSoft)
            }
        }
    }
}

@Composable
private fun FavouriteLinesPanel(lines: List<Line>, onOpenLine: (String) -> Unit) {
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Hairline()
            CalmRow(onClick = { onOpenLine(line.code) }) {
                LineBadge(line.code, strong = true)
                Spacer(Modifier.width(12.dp))
                Text(line.name, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Calm.colors.textSecondary)
            }
        }
    }
}

private fun LazyListScope.nearbySection(nearby: NearbyState, onRefresh: () -> Unit, onOpenStop: (String) -> Unit) {
    when (nearby) {
        NearbyState.Idle, NearbyState.Locating -> item("nearby-locating") {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_nearby_locating), style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Calm.colors.accent, trackColor = Calm.colors.accentSoft)
            }
        }
        NearbyState.PermissionNeeded -> item("nearby-permission") { PermissionPanel(onGranted = onRefresh) }
        NearbyState.LocationOff -> item("nearby-off") {
            MessagePanel(stringResource(R.string.home_nearby_location_off), stringResource(R.string.home_nearby_retry), onRefresh)
        }
        NearbyState.Unavailable -> item("nearby-unavailable") {
            MessagePanel(stringResource(R.string.home_nearby_unavailable), stringResource(R.string.home_nearby_retry), onRefresh)
        }
        is NearbyState.Found ->
            if (nearby.stops.isEmpty()) {
                item("nearby-none") { MessagePanel(stringResource(R.string.home_nearby_none)) }
            } else {
                item("nearby-list") {
                    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                        nearby.stops.forEachIndexed { index, item ->
                            if (index > 0) Hairline()
                            CalmRow(onClick = { onOpenStop(item.stop.code) }) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.stop.name, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        linesText(item.lineCodes, item.stop.code),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Calm.colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.home_distance_m, item.distanceMeters),
                                    style = MaterialTheme.typography.labelMedium.tabular(),
                                    color = Calm.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun linesText(lineCodes: List<String>, stopCode: String): String =
    if (lineCodes.isEmpty()) stringResource(R.string.home_stop_code, stopCode)
    else stringResource(R.string.home_stop_code_lines, stopCode, lineCodes.joinToString(", "))

/**
 * Asks for location only when the user taps the button. If Android stops showing the
 * dialog (denied twice), the button opens the app's settings instead.
 */
@Composable
private fun PermissionPanel(onGranted: () -> Unit) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            onGranted()
        } else {
            permanentlyDenied = activity != null && LocationProvider.PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        }
    }
    MessagePanel(
        text = stringResource(if (permanentlyDenied) R.string.home_nearby_permission_settings else R.string.home_nearby_permission),
        action = stringResource(if (permanentlyDenied) R.string.home_nearby_open_settings else R.string.home_nearby_allow),
        onAction = {
            if (permanentlyDenied) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                launcher.launch(LocationProvider.PERMISSIONS)
            }
        },
    )
}

@Composable
private fun MessagePanel(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Panel(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            PillButton(action, onAction)
        }
    }
}
