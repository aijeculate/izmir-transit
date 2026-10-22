package com.aijec.izmirtransit.feature.linemap

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Place
import com.aijec.izmirtransit.core.ui.ModeIcon
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.aijec.izmirtransit.core.ui.RollingText
import com.aijec.izmirtransit.core.ui.bouncySpring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.aijec.izmirtransit.core.map.MapPadding
import com.aijec.izmirtransit.core.map.PlanDestination
import com.aijec.izmirtransit.core.map.TransitMap
import com.aijec.izmirtransit.core.map.Viewport
import com.aijec.izmirtransit.core.model.MapLayer
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.ui.PillButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.TransitMode
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
import com.aijec.izmirtransit.core.ui.modeLabel
import com.aijec.izmirtransit.core.ui.rememberNow
import com.aijec.izmirtransit.core.ui.stopKindLabel
import com.aijec.izmirtransit.core.ui.tabular

@Composable
fun LineMapRoute(
    onOpenStop: (stopCode: String) -> Unit,
    /** Opens a line's timetable, at [stopCode] when the line was opened from a stop. */
    onOpenTimetable: (lineCode: String, stopCode: String?) -> Unit,
    onBack: () -> Unit = {},
    /** Opens the trip planner, optionally with a destination already chosen. */
    onPlanTrip: (PlanDestination?) -> Unit = {},
    /** Fullscreen hides the app's tab bar too; the host is told when it starts and ends. */
    onFullscreenChange: (Boolean) -> Unit = {},
    viewModel: LineMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.liveBuses.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val searchAtBottom by viewModel.searchAtBottom.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val bothDirections by viewModel.showBothDirections.collectAsStateWithLifecycle()
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
        overview = overview,
        layers = layers,
        isFavourite = isFavourite,
        onToggleFavourite = viewModel::toggleFavourite,
        onOpenTimetable = { state.lineCode?.let { onOpenTimetable(it, viewModel.fromStop) } },
        onClose = if (state.pinned) onBack else viewModel::clearLine,
        searchAtBottom = searchAtBottom,
        onLocate = {
            if (viewModel.hasLocationPermission()) viewModel.locate() else permissionLauncher.launch(LocationProvider.PERMISSIONS)
        },
        onQueryChange = viewModel::onQueryChange,
        onSelectLine = viewModel::selectLine,
        onOpenStop = { code ->
            viewModel.onQueryChange("")
            onOpenStop(code)
        },
        onMapStopClick = viewModel::onStopClick,
        onClosePreview = viewModel::closePreview,
        onMapClick = viewModel::onMapClick,
        onMapLongClick = viewModel::onMapLongClick,
        onViewportChanged = viewModel::onViewportChanged,
        onSetLayer = viewModel::setLayer,
        onPlanTrip = onPlanTrip,
        bothDirections = bothDirections,
        onSwitchDirection = viewModel::switchDirection,
        onFullscreenChange = onFullscreenChange,
    )
}

@Composable
fun LineMapScreen(
    state: LineMapUiState,
    live: LiveSnapshot<List<LiveBus>>,
    overview: List<OverviewRoute>,
    layers: Set<MapLayer>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onOpenTimetable: () -> Unit,
    onClose: () -> Unit,
    searchAtBottom: Boolean = false,
    onLocate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectLine: (String) -> Unit,
    onOpenStop: (String) -> Unit,
    onMapStopClick: (Stop) -> Unit = {},
    onClosePreview: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onMapLongClick: (LatLng) -> Unit = {},
    onViewportChanged: (Viewport) -> Unit = {},
    onSetLayer: (MapLayer, Boolean) -> Unit = { _, _ -> },
    onPlanTrip: (PlanDestination?) -> Unit = {},
    bothDirections: Boolean = false,
    onSwitchDirection: () -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    var showLayers by rememberSaveable { mutableStateOf(false) }
    // Fullscreen: only the map, for looking around on small screens; Back leaves it.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }
    DisposableEffect(Unit) { onDispose { onFullscreenChange(false) } }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // One direction at a time unless the user asked for both: simpler to read.
    val shownRoutes = remember(state.routes, state.direction, bothDirections) {
        if (bothDirections) state.routes else state.routes.filter { it.direction == state.direction }
    }
    val shownBuses = live.data.orEmpty().let { buses -> if (bothDirections) buses else buses.filter { it.direction == state.direction } }
    val shownLive = live.copy(data = live.data?.let { shownBuses })
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            routes = shownRoutes,
            routeMode = state.line?.mode ?: TransitMode.BUS,
            buses = shownBuses,
            // Keeps the route clear of the search pill above and the panels below.
            contentPadding = when {
                fullscreen -> MapPadding()
                searchAtBottom -> MapPadding(start = 24.dp, top = 60.dp, end = 24.dp, bottom = 400.dp)
                else -> MapPadding(start = 24.dp, top = 120.dp, end = 24.dp, bottom = 330.dp)
            },
            overview = if (state.lineCode == null) overview else emptyList(),
            // With a line chosen, only its own stops: others would bury it.
            areaStops = if (state.lineCode == null) state.areaStops else emptyList(),
            highlightedStop = state.preview?.stop,
            pin = state.pin,
            myLocationEnabled = state.locationEnabled,
            cameraRequest = state.cameraRequest,
            fitKey = state.lineCode,
            // The Map tab is rebuilt on every return from Home; pinned line screens keep
            // their camera in their own back-stack entry.
            memoryKey = if (state.pinned) null else "map-tab",
            onStopClick = onMapStopClick,
            onMapClick = { showLayers = false; onMapClick() },
            onMapLongClick = onMapLongClick,
            onViewportChanged = onViewportChanged,
            modifier = Modifier.fillMaxSize(),
        )
        if (fullscreen) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleIconButton(
                    Icons.Filled.FullscreenExit,
                    contentDescription = stringResource(R.string.linemap_fullscreen_exit),
                    onClick = { fullscreen = false },
                    glass = true,
                )
                LocateButton(state.locating, onLocate)
            }
            return@Box
        }
        if (!searchAtBottom) {
            Search(
                state = state,
                onQueryChange = onQueryChange,
                onSelectLine = onSelectLine,
                onOpenStop = onOpenStop,
                onPlanTrip = { onPlanTrip(null) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 12.dp, end = 12.dp, bottom = FloatingBarClearance),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val searching = state.query.isNotBlank()
            if (!searching) {
                if (showLayers) {
                    LayersPanel(layers, onSetLayer, onClose = { showLayers = false })
                }
                CircleIconButton(
                    Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.linemap_fullscreen),
                    onClick = { showLayers = false; fullscreen = true },
                    glass = true,
                )
                CircleIconButton(
                    Icons.Filled.Layers,
                    contentDescription = stringResource(R.string.linemap_layers),
                    onClick = { showLayers = !showLayers },
                    tint = if (showLayers) Calm.colors.accent else Calm.colors.text,
                    glass = true,
                )
                LocateButton(state.locating, onLocate)
                val preview = state.preview
                val pin = state.pin
                when {
                    preview != null -> StopPreviewPanel(
                        preview,
                        onOpenTransfer = { onMapStopClick(it) },
                        onOpenLine = { onClosePreview(); onSelectLine(it) },
                        onOpen = { onClosePreview(); onOpenStop(preview.stop.code) },
                        onPlan = { onPlanTrip(PlanDestination(preview.stop.name, preview.stop.location)) },
                        onClose = onClosePreview,
                    )
                    pin != null -> PinPanel(onPlan = { onPlanTrip(PlanDestination(null, pin)) }, onClose = onMapClick)
                    state.lineCode == null -> OverviewPanel(overview)
                    else -> LinePanel(
                        state, shownLive, isFavourite, onToggleFavourite, onOpenTimetable, onClose, bothDirections, onSwitchDirection,
                        otherDirectionBuses = live.data.orEmpty().size - shownBuses.size,
                    )
                }
            }
            if (searchAtBottom) {
                // Thumb-reach layout: results open above the search field.
                Search(state, onQueryChange, onSelectLine, onOpenStop, onPlanTrip = { onPlanTrip(null) }, resultsAbove = true, modifier = Modifier.imePadding())
            }
        }
    }
}

/** Which modes the map draws, and whether stops in view are shown: a small grid of toggles. */
@Composable
private fun LayersPanel(layers: Set<MapLayer>, onSet: (MapLayer, Boolean) -> Unit, onClose: () -> Unit) {
    val colors = Calm.colors
    Panel(Modifier.width(236.dp), glass = true, padding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.linemap_layers),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, stringResource(R.string.linemap_close_layers), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        MapLayer.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                pair.forEach { layer -> LayerChip(layer, on = layer in layers, onToggle = { onSet(layer, it) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LayerChip(layer: MapLayer, on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Calm.colors
    Row(
        modifier
            .height(36.dp)
            .clip(Calm.PillShape)
            .background(if (on) colors.accentSoft else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else colors.hairline, Calm.PillShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .alpha(if (on) 1f else 0.5f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mode = layerMode(layer)
        if (mode != null) {
            ModeIcon(mode, size = 20.dp)
        } else {
            Icon(Icons.Filled.Place, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(layerLabel(layer), style = MaterialTheme.typography.labelMedium, color = if (on) colors.onAccentSoft else colors.text, maxLines = 1)
    }
}

private fun layerMode(layer: MapLayer): TransitMode? = when (layer) {
    MapLayer.BUS_LINES -> TransitMode.BUS
    MapLayer.METRO -> TransitMode.METRO
    MapLayer.TRAM -> TransitMode.TRAM
    MapLayer.IZBAN -> TransitMode.IZBAN
    MapLayer.FERRY -> TransitMode.FERRY
    MapLayer.STOPS -> null
}

@Composable
private fun layerLabel(layer: MapLayer): String = when (layer) {
    MapLayer.STOPS -> stringResource(R.string.linemap_layer_stops)
    else -> modeLabel(layerMode(layer)!!)
}

/** A tapped stop: what it is and which lines call there, before opening it. */
@Composable
private fun StopPreviewPanel(
    preview: StopPreview,
    onOpenTransfer: (Stop) -> Unit,
    onOpenLine: (String) -> Unit,
    onOpen: () -> Unit,
    onPlan: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), glass = true, padding = PaddingValues(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preview.stop.name, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stopKindLabel(preview.stop), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            CircleIconButton(Icons.Filled.Close, stringResource(R.string.linemap_close_stop), onClick = onClose, tint = colors.textSecondary, plain = true)
        }
        if (preview.lines.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 8.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A busy stop can have dozens of lines; the stop screen lists them all.
                // Ferry routes are all "Vapur"; their names tell them apart.
                preview.lines.take(MAX_PREVIEW_LINES).forEach { line ->
                    val open = Modifier.clickable { onOpenLine(line.code) }
                    if (line.mode == TransitMode.FERRY) LineBadge(line.name, open, mode = line.mode) else LineBadge(line, open)
                }
                if (preview.lines.size > MAX_PREVIEW_LINES) {
                    Text("+${preview.lines.size - MAX_PREVIEW_LINES}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }
        if (preview.transfers.isNotEmpty()) {
            // Interchanges: tap to jump to the connected station.
            FlowRow(
                Modifier.padding(top = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.linemap_transfer),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                preview.transfers.forEach { transfer ->
                    Row(
                        Modifier
                            .clip(Calm.PillShape)
                            .clickable { onOpenTransfer(transfer) }
                            .background(colors.accentSoft.copy(alpha = 0.5f))
                            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModeIcon(transfer.mode, size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(transfer.name, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.linemap_open_stop), onClick = onOpen, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.linemap_directions_here), onClick = onPlan, modifier = Modifier.weight(1f), secondary = true)
        }
    }
}

private const val MAX_PREVIEW_LINES = 12

/** A long-pressed point: offer a trip there. */
@Composable
private fun PinPanel(onPlan: () -> Unit, onClose: () -> Unit) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), glass = true, padding = PaddingValues(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.linemap_pin_title), style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
            CircleIconButton(Icons.Filled.Close, stringResource(R.string.linemap_close_stop), onClick = onClose, tint = colors.textSecondary, plain = true)
        }
        PillButton(stringResource(R.string.linemap_directions_here), onClick = onPlan, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp))
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
    onPlanTrip: () -> Unit,
    modifier: Modifier = Modifier,
    resultsAbove: Boolean = false,
) {
    val focus = LocalFocusManager.current
    // The search pill with the trip planner's button beside it.
    val field: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillSearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.linemap_search_hint),
                glass = true,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(
                Icons.Filled.Directions,
                contentDescription = stringResource(R.string.linemap_plan_trip),
                onClick = onPlanTrip,
                tint = Calm.colors.accent,
                glass = true,
            )
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!resultsAbove) field()
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
                                LineBadge(line)
                                Spacer(Modifier.width(12.dp))
                                Text(line.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.text)
                            }
                        }
                    }
                    if (state.stopResults.isNotEmpty()) {
                        item("stops-header") { ResultHeader(stringResource(R.string.linemap_search_stops)) }
                        items(state.stopResults, key = { "stop-${it.stop.code}" }) { (stop, lines) ->
                            CalmRow(onClick = { focus.clearFocus(); onOpenStop(stop.code) }, padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                ModeIcon(stop.mode, size = 28.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(stop.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.text)
                                    Text(
                                        when {
                                            stop.mode != TransitMode.BUS ->
                                                stopKindLabel(stop) + lines.map { it.displayCode }.distinct().filter { stop.mode != TransitMode.FERRY }
                                                    .joinToString(", ").let { if (it.isEmpty()) "" else " · $it" }
                                            lines.isEmpty() -> stringResource(R.string.linemap_stop_code, stop.code)
                                            else -> stringResource(R.string.linemap_stop_code_lines, stop.code, lines.joinToString(", ") { it.displayCode })
                                        },
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
        if (resultsAbove) field()
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
    onClose: () -> Unit,
    bothDirections: Boolean = true,
    onSwitchDirection: () -> Unit = {},
    /** Buses running the direction not shown, so "none" doesn't hide them. */
    otherDirectionBuses: Int = 0,
) {
    val lineCode = state.lineCode ?: return
    var showAnnouncements by rememberSaveable(lineCode) { mutableStateOf(false) }
    // The panel grows and shrinks on a spring (announcements opening, a direction switched).
    Panel(Modifier.fillMaxWidth().animateContentSize(bouncySpring()), glass = true, padding = PaddingValues(0.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.line?.let { LineBadge(it, strong = true) } ?: LineBadge(lineCode, strong = true)
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
            CircleIconButton(
                Icons.Filled.Close,
                contentDescription = stringResource(if (state.pinned) R.string.linemap_back else R.string.linemap_close),
                onClick = onClose,
                tint = Calm.colors.textSecondary,
                plain = true,
            )
        }
        state.line?.let { line ->
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val available = if (line.mode == TransitMode.BUS) Direction.entries else state.routes.map { it.direction }
                // Rail and ferry name the route's last station: a short train ends before the line does.
                fun towards(direction: Direction): String =
                    state.routes.takeIf { line.mode != TransitMode.BUS }?.firstOrNull { it.direction == direction }?.stops?.lastOrNull()?.name
                        ?: if (direction == Direction.OUTBOUND) line.destination else line.origin
                if (bothDirections || available.size < 2) {
                    available.forEach { direction ->
                        DirectionLegend(
                            direction,
                            stringResource(R.string.linemap_towards, towards(direction)),
                            state.nextDepartures[direction],
                        )
                    }
                } else {
                    // One direction shown: where it's going, and a switch to the other.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DirectionLegend(
                                state.direction,
                                stringResource(R.string.linemap_towards, towards(state.direction)),
                                state.nextDepartures[state.direction],
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PillButton(
                            stringResource(R.string.linemap_switch_direction),
                            onClick = onSwitchDirection,
                            secondary = true,
                        )
                    }
                }
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
            Box(Modifier.weight(1f)) {
                if (state.line?.mode?.hasLiveVehicles == false) {
                    Text(
                        stringResource(R.string.linemap_scheduled_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Calm.colors.textSecondary,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    val line = state.line
                    val otherTowards = line?.let { if (state.direction.opposite == Direction.OUTBOUND) it.destination else it.origin }
                    LiveStatusLine(
                        live,
                        towards = line?.let { if (state.direction == Direction.OUTBOUND) it.destination else it.origin }.takeIf { !bothDirections },
                        otherDirection = if (bothDirections) 0 else otherDirectionBuses,
                        otherTowards = otherTowards,
                    )
                }
            }
            if (state.line != null) TextAction(stringResource(R.string.linemap_timetable), onOpenTimetable)
        }
    }
}

/** Shown on the Map tab before a line is chosen, over the all-lines overlay. */
@Composable
private fun OverviewPanel(overview: List<OverviewRoute>) {
    // Two shapes (one per direction) per bus line.
    val busLines = overview.count { it.mode == TransitMode.BUS } / 2
    // One colour per mode for the legend: the first line's (tram lines each have their own).
    val legend = overview.filter { it.mode != TransitMode.BUS }.distinctBy { it.mode }.sortedBy { it.mode }
    Panel(Modifier.fillMaxWidth(), glass = true) {
        Text(stringResource(R.string.linemap_overview_title), style = MaterialTheme.typography.titleSmall, color = Calm.colors.text)
        Spacer(Modifier.size(2.dp))
        Text(
            if (busLines == 0) stringResource(R.string.linemap_overview_hint)
            else pluralStringResource(R.plurals.linemap_overview_count, busLines, busLines),
            style = MaterialTheme.typography.bodyMedium,
            color = Calm.colors.textSecondary,
        )
        if (legend.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                legend.forEach { route ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeIcon(route.mode, size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(modeLabel(route.mode), style = MaterialTheme.typography.labelMedium, color = Calm.colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionLegend(direction: Direction, label: String, next: NextDepartures? = null) {
    val dot by animateColorAsState(TransitColors.forDirection(direction), tween(420), label = "direction")
    // Switching direction: the new label and times slide in as the old ones leave.
    RollingText("$direction|$label") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(dot)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Calm.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (next != null) {
                Text(
                    if (next.minutes.isEmpty()) stringResource(R.string.linemap_schedule_none, next.fromStop)
                    else stringResource(R.string.linemap_schedule_from, next.fromStop, next.minutes.joinToString("  ·  ") { TimetableRepository.formatClock(it) }),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = Calm.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

/** Explicit loading, empty, fresh, stale and unavailable states; never a blank. */
@Composable
private fun LiveStatusLine(live: LiveSnapshot<List<LiveBus>>, towards: String? = null, otherDirection: Int = 0, otherTowards: String? = null) {
    val now by rememberNow()
    val age = live.lastUpdated?.let { formatAge(it, now) }.orEmpty()
    val buses = live.data.orEmpty()
    val colors = Calm.colors
    val (text, color) = when (live.status) {
        LiveStatus.LOADING -> stringResource(R.string.linemap_live_loading) to colors.textSecondary
        LiveStatus.FRESH -> when {
            buses.isNotEmpty() -> pluralStringResource(R.plurals.linemap_live_count, buses.size, buses.size, age) to colors.text
            // One direction shown and every bus is heading the other way: say so, not "none".
            otherDirection > 0 && towards != null && otherTowards != null ->
                pluralStringResource(R.plurals.linemap_live_other_direction, otherDirection, towards, otherDirection, otherTowards) to colors.text
            else -> stringResource(R.string.linemap_live_none) to colors.textSecondary
        }
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
