package com.aijec.izmirtransit.feature.home

import androidx.compose.material.icons.filled.Map
import com.aijec.izmirtransit.core.ui.ModeIcon
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import com.aijec.izmirtransit.core.data.repository.SavedPlaces
import com.aijec.izmirtransit.core.model.geo.LatLng
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Settings
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
import com.aijec.izmirtransit.core.data.repository.FavouriteItem
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.CircleIconButton
import com.aijec.izmirtransit.core.ui.EtaText
import com.aijec.izmirtransit.core.ui.FloatingBarClearance
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.PillButton
import com.aijec.izmirtransit.core.ui.SectionLabel
import com.aijec.izmirtransit.core.ui.entrance
import com.aijec.izmirtransit.core.ui.rememberEntrance
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow
import com.aijec.izmirtransit.core.ui.stopKindLabel
import com.aijec.izmirtransit.core.ui.tabular
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(
    onOpenStop: (String) -> Unit,
    onOpenLine: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPlanTrip: () -> Unit = {},
    onShowOnMap: () -> Unit = {},
    onPlanTo: (name: String, location: LatLng) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val arrivals by viewModel.arrivals.collectAsStateWithLifecycle()
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()

    // Refresh the location each time the screen comes back into view.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshNearby() }

    HomeScreen(
        favourites = favourites,
        arrivals = arrivals,
        boards = boards,
        nearby = nearby,
        onRefreshNearby = viewModel::refreshNearby,
        onOpenStop = onOpenStop,
        onOpenLine = onOpenLine,
        onOpenSettings = onOpenSettings,
        onPlanTrip = onPlanTrip,
        onShowOnMap = { code -> viewModel.showOnMap(code); onShowOnMap() },
        saved = saved,
        onPlanTo = onPlanTo,
        editActions = FavouriteEditActions(viewModel::moveFavourite, viewModel::removeFavourite),
    )
}

/** What the favourites' edit mode can do: move a stop or line by some places, or remove it. */
class FavouriteEditActions(
    val move: (FavouriteItem, Int) -> Unit = { _, _ -> },
    val remove: (FavouriteItem) -> Unit = {},
)

@Composable
fun HomeScreen(
    favourites: FavouritesState,
    arrivals: Map<String, LiveSnapshot<List<ApproachingBus>>>,
    boards: Map<String, StationBoard> = emptyMap(),
    nearby: NearbyState,
    onRefreshNearby: () -> Unit,
    onOpenStop: (String) -> Unit,
    onOpenLine: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onPlanTrip: () -> Unit = {},
    onShowOnMap: (String) -> Unit = {},
    editActions: FavouriteEditActions = FavouriteEditActions(),
    saved: SavedPlaces = SavedPlaces(),
    onPlanTo: (name: String, location: LatLng) -> Unit = { _, _ -> },
) {
    val hasFavourites = favourites.entries.isNotEmpty()
    var editMode by rememberSaveable { mutableStateOf(false) }
    val editing = editMode && hasFavourites
    // Cards rise in one after another the first time Home is shown.
    val entrance = rememberEntrance()
    val groups = favouriteGroups(favourites.entries)
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
        item("header") {
            Row(Modifier.entrance(entrance, 0), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { Greeting() }
                CircleIconButton(Icons.Filled.Settings, stringResource(R.string.home_settings), onOpenSettings, tint = Calm.colors.textSecondary)
            }
        }
        item("where-to") { Box(Modifier.entrance(entrance, 1)) { WhereTo(onPlanTrip, saved, onPlanTo) } }

        item("fav-label") {
            SectionLabel(stringResource(R.string.home_favourites), Modifier.entrance(entrance, 2)) {
                if (hasFavourites) {
                    Text(
                        stringResource(if (editing) R.string.home_favourites_done else R.string.home_favourites_edit),
                        style = MaterialTheme.typography.labelLarge,
                        color = Calm.colors.accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { editMode = !editing }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (favourites.loaded && !hasFavourites) {
            item("fav-empty") { EmptyFavourites() }
        }
        if (editing) {
            item("fav-edit") {
                EditFavouritesPanel(favourites.entries, onMove = editActions.move, onRemove = editActions.remove) { entry ->
                    when (entry) {
                        is FavouriteEntry.StopEntry -> {
                            ModeIcon(entry.favourite.stop.mode, size = 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(entry.favourite.stop.name, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        is FavouriteEntry.LineEntry -> {
                            LineBadge(entry.line, strong = true)
                            Spacer(Modifier.width(12.dp))
                            Text(entry.line.name, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            // In the user's order; neighbouring lines share one panel.
            groups.forEachIndexed { i, group ->
                when (group) {
                    is FavouriteEntry.StopEntry -> item("fav-stop-${group.favourite.stop.code}") {
                        Box(Modifier.entrance(entrance, 3 + i)) {
                            FavouriteStopPanel(
                                group.favourite,
                                arrivals[group.favourite.stop.code],
                                boards[group.favourite.stop.code],
                                onClick = { onOpenStop(group.favourite.stop.code) },
                                onShowOnMap = { onShowOnMap(group.favourite.stop.code) },
                            )
                        }
                    }
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val lines = (group as List<FavouriteEntry.LineEntry>).map { it.line }
                        item("fav-lines-${lines.first().code}") { Box(Modifier.entrance(entrance, 3 + i)) { FavouriteLinesPanel(lines, onOpenLine) } }
                    }
                }
            }
        }

        item("nearby-label") {
            SectionLabel(stringResource(R.string.home_nearby), Modifier.entrance(entrance, 3 + groups.size)) {
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
/** The trip planner's front door: looks like a search field, opens the planner. */
@Composable
private fun WhereTo(onClick: () -> Unit, saved: SavedPlaces, onPlanTo: (String, LatLng) -> Unit) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        CalmRow(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
            Icon(Icons.Filled.Directions, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_where_to), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        }
        // "Take me home" in one tap; set them in the trip planner's search.
        val shortcuts = listOfNotNull(
            saved.home?.let { Triple(Icons.Filled.Home, R.string.home_go_home, it) },
            saved.work?.let { Triple(Icons.Filled.Work, R.string.home_go_work, it) },
        )
        if (shortcuts.isNotEmpty()) {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, label, place) in shortcuts) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(colors.accentSoft)
                            .clickable(role = Role.Button) { onPlanTo(place.name, place.location) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    }
                }
            }
        }
    }
}

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
private fun FavouriteStopPanel(
    favourite: FavouriteStop,
    arrivals: LiveSnapshot<List<ApproachingBus>>?,
    board: StationBoard?,
    onClick: () -> Unit,
    onShowOnMap: () -> Unit = {},
) {
    val snapshot = arrivals ?: LoadingArrivals
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        CalmRow(onClick = onClick, padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
            ModeIcon(favourite.stop.mode, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(favourite.stop.name, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    linesText(favourite.stop, favourite.lines),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleIconButton(Icons.Filled.Map, stringResource(R.string.home_show_on_map), onShowOnMap, tint = colors.accent, plain = true)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val buses = snapshot.data.orEmpty()
            val linesByCode = favourite.lines.associateBy { it.code }
            when {
                favourite.stop.mode != TransitMode.BUS -> when {
                    board == null -> {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.accentSoft)
                    }
                    board.departures.isEmpty() -> Text(stringResource(R.string.home_departures_none), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    else -> board.departures.forEach { departure ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(departure.line)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                departure.headsign,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            EtaText(departure.minutesAfterMidnight - board.nowMinute)
                        }
                    }
                }
                snapshot.status == LiveStatus.LOADING -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.accentSoft)
                }
                snapshot.status == LiveStatus.UNAVAILABLE -> Text(liveErrorText(snapshot.lastError), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                buses.isEmpty() -> Text(stringResource(R.string.home_arrivals_none), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                else -> buses.take(3).forEach { bus ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        linesByCode[bus.lineCode]?.let { LineBadge(it) } ?: LineBadge(bus.lineCode)
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

/** Stops on their own, runs of neighbouring lines as one list each. */
private fun favouriteGroups(entries: List<FavouriteEntry>): List<Any> {
    val groups = mutableListOf<Any>()
    for (entry in entries) {
        when (entry) {
            is FavouriteEntry.StopEntry -> groups.add(entry)
            is FavouriteEntry.LineEntry -> {
                @Suppress("UNCHECKED_CAST")
                val run = groups.lastOrNull() as? MutableList<FavouriteEntry.LineEntry>
                if (run != null) run.add(entry) else groups.add(mutableListOf(entry))
            }
        }
    }
    return groups
}

/** Favourites as a compact list with up, down and remove buttons on each row. */
@Composable
private fun EditFavouritesPanel(
    items: List<FavouriteEntry>,
    onMove: (FavouriteItem, Int) -> Unit,
    onRemove: (FavouriteItem) -> Unit,
    label: @Composable RowScope.(FavouriteEntry) -> Unit,
) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
        items.forEachIndexed { index, item ->
            if (index > 0) Hairline()
            val code = item.item
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { label(item) }
                IconButton(onClick = { onMove(code, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.home_favourite_move_up), tint = if (index > 0) colors.accent else colors.hairline)
                }
                IconButton(onClick = { onMove(code, 1) }, enabled = index < items.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.home_favourite_move_down), tint = if (index < items.lastIndex) colors.accent else colors.hairline)
                }
                IconButton(onClick = { onRemove(code) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.home_favourite_remove), tint = colors.textSecondary)
                }
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
                LineBadge(line, strong = true)
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
                                ModeIcon(item.stop.mode, size = 30.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.stop.name, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        linesText(item.stop, item.lines),
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
private fun linesText(stop: Stop, lines: List<Line>): String = when {
    // Stations say what they are ("Metro istasyonu · M1"); every ferry route is just "Vapur".
    stop.mode == TransitMode.FERRY -> stopKindLabel(stop)
    stop.mode != TransitMode.BUS -> stopKindLabel(stop) + " · " + lines.map { it.displayCode }.distinct().joinToString(", ")
    lines.isEmpty() -> stringResource(R.string.home_stop_code, stop.code)
    else -> stringResource(R.string.home_stop_code_lines, stop.code, lines.joinToString(", ") { it.displayCode })
}

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
