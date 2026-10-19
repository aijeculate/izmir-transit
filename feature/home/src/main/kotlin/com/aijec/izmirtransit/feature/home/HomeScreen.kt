package com.aijec.izmirtransit.feature.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.liveErrorText

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    favourites: FavouritesState,
    arrivals: Map<String, LiveSnapshot<List<ApproachingBus>>>,
    nearby: NearbyState,
    onRefreshNearby: () -> Unit,
    onOpenStop: (String) -> Unit,
    onOpenLine: (String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("favourites-header") { SectionHeader(stringResource(R.string.home_favourites)) }
            if (favourites.loaded && favourites.stops.isEmpty() && favourites.lines.isEmpty()) {
                item("favourites-empty") { HintCard(stringResource(R.string.home_favourites_empty)) }
            }
            items(favourites.stops, key = { "fav-stop-${it.stop.code}" }) { favourite ->
                FavouriteStopCard(favourite, arrivals[favourite.stop.code], onClick = { onOpenStop(favourite.stop.code) })
            }
            items(favourites.lines, key = { "fav-line-${it.code}" }) { line ->
                Card(Modifier.fillMaxWidth().clickable { onOpenLine(line.code) }) {
                    Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(line.code)
                        Spacer(Modifier.width(12.dp))
                        Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item("nearby-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.home_nearby), Modifier.weight(1f))
                    if (nearby is NearbyState.Found || nearby is NearbyState.Unavailable) {
                        IconButton(onClick = onRefreshNearby) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_nearby_refresh))
                        }
                    }
                }
            }
            nearbySection(nearby, onRefreshNearby, onOpenStop)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.nearbySection(
    nearby: NearbyState,
    onRefresh: () -> Unit,
    onOpenStop: (String) -> Unit,
) {
    when (nearby) {
        NearbyState.Idle, NearbyState.Locating -> item("nearby-locating") {
            Column {
                Text(stringResource(R.string.home_nearby_locating), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        NearbyState.PermissionNeeded -> item("nearby-permission") { PermissionCard(onGranted = onRefresh) }
        NearbyState.LocationOff -> item("nearby-off") {
            HintCard(stringResource(R.string.home_nearby_location_off), action = stringResource(R.string.home_nearby_retry), onAction = onRefresh)
        }
        NearbyState.Unavailable -> item("nearby-unavailable") {
            HintCard(stringResource(R.string.home_nearby_unavailable), action = stringResource(R.string.home_nearby_retry), onAction = onRefresh)
        }
        is NearbyState.Found ->
            if (nearby.stops.isEmpty()) {
                item("nearby-none") { HintCard(stringResource(R.string.home_nearby_none)) }
            } else {
                items(nearby.stops, key = { "near-${it.stop.code}" }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenStop(item.stop.code) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.stop.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    linesText(item.lineCodes, item.stop.code),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.home_distance_m, item.distanceMeters), style = MaterialTheme.typography.titleSmall)
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

@Composable
private fun FavouriteStopCard(favourite: FavouriteStop, arrivals: LiveSnapshot<List<ApproachingBus>>?, onClick: () -> Unit) {
    val snapshot = arrivals ?: LoadingArrivals
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(favourite.stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                linesText(favourite.lineCodes, favourite.stop.code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val buses = snapshot.data.orEmpty()
            when {
                snapshot.status == LiveStatus.LOADING -> Text(stringResource(R.string.home_arrivals_loading), style = MaterialTheme.typography.bodyMedium)
                snapshot.status == LiveStatus.UNAVAILABLE -> Text(liveErrorText(snapshot.lastError), color = MaterialTheme.colorScheme.error)
                buses.isEmpty() -> Text(stringResource(R.string.home_arrivals_none), style = MaterialTheme.typography.bodyMedium)
                else -> buses.take(3).forEach { bus ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(bus.lineCode)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pluralStringResource(R.plurals.home_stops_away, bus.stopsRemaining, bus.stopsRemaining),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (bus.eta.minutes <= 0) stringResource(R.string.home_eta_now) else stringResource(R.string.home_eta_minutes, bus.eta.minutes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (bus.eta.basis == EtaBasis.UNRELIABLE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (snapshot.status == LiveStatus.STALE) {
                Text(liveErrorText(snapshot.lastError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/**
 * Asks for location only when the user taps the button. If Android stops showing the
 * dialog (denied twice), the button opens the app's settings instead.
 */
@Composable
private fun PermissionCard(onGranted: () -> Unit) {
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
    HintCard(
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
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HintCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

