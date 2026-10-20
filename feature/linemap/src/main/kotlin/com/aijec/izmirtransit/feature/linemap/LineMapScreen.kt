package com.aijec.izmirtransit.feature.linemap

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.AnnouncementCard
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.CircleIconButton
import com.aijec.izmirtransit.core.ui.ConnectionChips
import com.aijec.izmirtransit.core.ui.Dot
import com.aijec.izmirtransit.core.ui.FavouriteButton
import com.aijec.izmirtransit.core.ui.FloatingBarClearance
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.PillSearchField
import com.aijec.izmirtransit.core.ui.TextAction
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.formatAge
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow

@Composable
fun LineMapRoute(
    onOpenStop: (stopCode: String) -> Unit,
    onOpenTimetable: (lineCode: String) -> Unit,
    viewModel: LineMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.liveBuses.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.locate() else viewModel.onLocationPermissionDenied()
    }
    // Problems are one-off messages, not persistent UI.
    val problem = state.locationProblem
    LaunchedEffect(problem) {
        if (problem != null) {
            val message = when (problem) {
                LocationProblem.PERMISSION -> R.string.linemap_location_permission
                LocationProblem.OFF -> R.string.linemap_location_off
                LocationProblem.UNAVAILABLE -> R.string.linemap_location_unavailable
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeLocationProblem()
        }
    }

    LineMapScreen(
        state = state,
        live = live,
        isFavourite = isFavourite,
        onToggleFavourite = viewModel::toggleFavourite,
        onOpenTimetable = { onOpenTimetable(state.lineCode) },
        onLocate = {
            if (viewModel.hasLocationPermission()) viewModel.locate() else permissionLauncher.launch(LocationProvider.PERMISSIONS)
        },
        onQueryChange = viewModel::onQueryChange,
        onSelectLine = viewModel::selectLine,
        onOpenStop = { code ->
            viewModel.onQueryChange("")
            onOpenStop(code)
        },
    )
}

@Composable
fun LineMapScreen(
    state: LineMapUiState,
    live: LiveSnapshot<List<LiveBus>>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onOpenTimetable: () -> Unit,
    onLocate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectLine: (String) -> Unit,
    onOpenStop: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            routes = state.routes,
            buses = live.data.orEmpty(),
            // Keeps the route clear of the search pill above and the panels below.
            contentPadding = MapPadding(start = 32.dp, top = 120.dp, end = 32.dp, bottom = 330.dp),
            onStopClick = onOpenStop,
            userLocation = state.userLocation,
            nearbyStops = state.nearbyStops,
            cameraRequest = state.cameraRequest,
            modifier = Modifier.fillMaxSize(),
        )
        Search(
            state = state,
            onQueryChange = onQueryChange,
            onSelectLine = onSelectLine,
            onOpenStop = onOpenStop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 12.dp, end = 12.dp, bottom = FloatingBarClearance),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocateButton(state.locating, onLocate)
            LinePanel(state, live, isFavourite, onToggleFavourite, onOpenTimetable)
        }
    }
}

@Composable
private fun LocateButton(locating: Boolean, onLocate: () -> Unit) {
    if (locating) {
        Panel(glass = true, padding = PaddingValues(13.dp), modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Calm.colors.accent)
        }
    } else {
        CircleIconButton(
            Icons.Filled.LocationOn,
            contentDescription = stringResource(R.string.linemap_my_location),
            onClick = onLocate,
            tint = Calm.colors.accent,
            glass = true,
        )
    }
}

/** One box for lines and stops; Turkish-aware matching happens in the repositories. */
@Composable
private fun Search(
    state: LineMapUiState,
    onQueryChange: (String) -> Unit,
    onSelectLine: (String) -> Unit,
    onOpenStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.linemap_search_hint),
            glass = true,
        )
        if (state.hasResults || state.query.isNotBlank()) {
            Panel(Modifier.fillMaxWidth(), glass = true, padding = PaddingValues(vertical = 6.dp)) {
                if (!state.hasResults) {
                    Text(
                        stringResource(R.string.linemap_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Calm.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (state.lineResults.isNotEmpty()) {
                        item("lines-header") { ResultHeader(stringResource(R.string.linemap_search_lines)) }
                        items(state.lineResults, key = { "line-${it.code}" }) { line ->
                            CalmRow(onClick = { focus.clearFocus(); onSelectLine(line.code) }, padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                LineBadge(line.code)
                                Spacer(Modifier.width(12.dp))
                                Text(line.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.text)
                            }
                        }
                    }
                    if (state.stopResults.isNotEmpty()) {
                        item("stops-header") { ResultHeader(stringResource(R.string.linemap_search_stops)) }
                        items(state.stopResults, key = { "stop-${it.stop.code}" }) { (stop, lineCodes) ->
                            CalmRow(onClick = { focus.clearFocus(); onOpenStop(stop.code) }, padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                Column {
                                    Text(stop.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.text)
                                    Text(
                                        if (lineCodes.isEmpty()) stringResource(R.string.linemap_stop_code, stop.code)
                                        else stringResource(R.string.linemap_stop_code_lines, stop.code, lineCodes.joinToString(", ")),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Calm.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Calm.colors.textSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun LinePanel(
    state: LineMapUiState,
    live: LiveSnapshot<List<LiveBus>>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onOpenTimetable: () -> Unit,
) {
    var showAnnouncements by rememberSaveable(state.lineCode) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth().animateContentSize(), glass = true, padding = PaddingValues(0.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(state.lineCode, strong = true)
            Spacer(Modifier.width(10.dp))
            Text(
                state.line?.name ?: if (state.loadingRoute) "" else stringResource(R.string.linemap_unknown_line),
                style = MaterialTheme.typography.titleSmall,
                color = Calm.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.line != null) FavouriteButton(isFavourite, onToggleFavourite, plain = true)
        }
        state.line?.let { line ->
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DirectionLegend(Direction.OUTBOUND, stringResource(R.string.linemap_towards, line.destination))
                DirectionLegend(Direction.RETURN, stringResource(R.string.linemap_towards, line.origin))
                ConnectionChips(state.connections, Modifier.padding(top = 4.dp))
            }
        }
        if (state.announcements.isNotEmpty()) {
            Row(
                Modifier
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                    .clip(Calm.PillShape)
                    .clickable(role = Role.Button) { showAnnouncements = !showAnnouncements }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Calm.colors.onWarningSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    pluralStringResource(R.plurals.linemap_announcements, state.announcements.size, state.announcements.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = Calm.colors.onWarningSoft,
                )
            }
            if (showAnnouncements) {
                Column(
                    Modifier.padding(horizontal = 12.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.announcements.forEach { AnnouncementCard(it, showLine = false) }
                }
            }
        }
        Hairline(Modifier.padding(top = 10.dp), inset = 16.dp)
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { LiveStatusLine(live) }
            if (state.line != null) TextAction(stringResource(R.string.linemap_timetable), onOpenTimetable)
        }
    }
}

@Composable
private fun DirectionLegend(direction: Direction, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(TransitColors.forDirection(direction))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Explicit loading, empty, fresh, stale and unavailable states; never a blank. */
@Composable
private fun LiveStatusLine(live: LiveSnapshot<List<LiveBus>>) {
    val now by rememberNow()
    val age = live.lastUpdated?.let { formatAge(it, now) }.orEmpty()
    val buses = live.data.orEmpty()
    val colors = Calm.colors
    val (text, color) = when (live.status) {
        LiveStatus.LOADING -> stringResource(R.string.linemap_live_loading) to colors.textSecondary
        LiveStatus.FRESH ->
            if (buses.isEmpty()) stringResource(R.string.linemap_live_none) to colors.textSecondary
            else pluralStringResource(R.plurals.linemap_live_count, buses.size, buses.size, age) to colors.text
        LiveStatus.STALE -> stringResource(R.string.linemap_live_stale, liveErrorText(live.lastError), age) to colors.onWarningSoft
        LiveStatus.UNAVAILABLE -> stringResource(R.string.linemap_live_unavailable, liveErrorText(live.lastError)) to colors.onWarningSoft
    }
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live.status == LiveStatus.FRESH && buses.isNotEmpty()) {
                Dot(colors.accent, size = 8.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        }
        if (live.status == LiveStatus.LOADING) {
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.accentSoft)
        }
    }
}
