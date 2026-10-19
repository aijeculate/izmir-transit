package com.aijec.izmirtransit.feature.linemap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import com.aijec.izmirtransit.core.ui.LineBadge
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
            contentPadding = MapPadding(start = 32.dp, top = 140.dp, end = 32.dp, bottom = 240.dp),
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
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = onLocate) {
                if (state.locating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.LocationOn, contentDescription = stringResource(R.string.linemap_my_location))
                }
            }
            LineCard(
                state = state,
                live = live,
                isFavourite = isFavourite,
                onToggleFavourite = onToggleFavourite,
                onOpenTimetable = onOpenTimetable,
            )
        }
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
    Card(modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.linemap_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        if (state.hasResults) {
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.lineResults.isNotEmpty()) {
                    item("lines-header") { SectionHeader(stringResource(R.string.linemap_search_lines)) }
                    items(state.lineResults, key = { "line-${it.code}" }) { line ->
                        ResultRow(onClick = { focus.clearFocus(); onSelectLine(line.code) }) {
                            LineBadge(line.code)
                            Spacer(Modifier.width(12.dp))
                            Text(line.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.stopResults.isNotEmpty()) {
                    item("stops-header") { SectionHeader(stringResource(R.string.linemap_search_stops)) }
                    items(state.stopResults, key = { "stop-${it.stop.code}" }) { (stop, lineCodes) ->
                        ResultRow(onClick = { focus.clearFocus(); onOpenStop(stop.code) }) {
                            Column {
                                Text(stop.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (lineCodes.isEmpty()) stringResource(R.string.linemap_stop_code, stop.code)
                                    else stringResource(R.string.linemap_stop_code_lines, stop.code, lineCodes.joinToString(", ")),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else if (state.query.isNotBlank()) {
            Text(
                stringResource(R.string.linemap_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun LineCard(
    state: LineMapUiState,
    live: LiveSnapshot<List<LiveBus>>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onOpenTimetable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAnnouncements by rememberSaveable(state.lineCode) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(state.lineCode)
                Spacer(Modifier.width(12.dp))
                Text(
                    state.line?.name ?: if (state.loadingRoute) "" else stringResource(R.string.linemap_unknown_line),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.line != null) {
                    IconButton(onClick = onToggleFavourite) {
                        Icon(
                            if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(if (isFavourite) R.string.linemap_favourite_remove else R.string.linemap_favourite_add),
                            tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.line?.let { line ->
                DirectionLegend(Direction.OUTBOUND, stringResource(R.string.linemap_towards, line.destination))
                DirectionLegend(Direction.RETURN, stringResource(R.string.linemap_towards, line.origin))
            }
            if (state.announcements.isNotEmpty()) {
                TextButton(onClick = { showAnnouncements = !showAnnouncements }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.linemap_announcements, state.announcements.size, state.announcements.size))
                }
                if (showAnnouncements) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.announcements.forEach { AnnouncementCard(it, showLine = false) }
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LiveStatusLine(live) }
                if (state.line != null) {
                    TextButton(onClick = onOpenTimetable) { Text(stringResource(R.string.linemap_timetable)) }
                }
            }
        }
    }
}

@Composable
private fun DirectionLegend(direction: Direction, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(TransitColors.forDirection(direction), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Explicit loading, empty, fresh, stale and unavailable states; never a blank. */
@Composable
private fun LiveStatusLine(live: LiveSnapshot<List<LiveBus>>) {
    val now by rememberNow()
    val age = live.lastUpdated?.let { formatAge(it, now) }.orEmpty()
    val buses = live.data.orEmpty()
    val (text, color) = when (live.status) {
        LiveStatus.LOADING -> stringResource(R.string.linemap_live_loading) to MaterialTheme.colorScheme.onSurfaceVariant
        LiveStatus.FRESH ->
            if (buses.isEmpty()) stringResource(R.string.linemap_live_none) to MaterialTheme.colorScheme.onSurfaceVariant
            else pluralStringResource(R.plurals.linemap_live_count, buses.size, buses.size, age) to MaterialTheme.colorScheme.onSurface
        LiveStatus.STALE -> stringResource(R.string.linemap_live_stale, liveErrorText(live.lastError), age) to MaterialTheme.colorScheme.tertiary
        LiveStatus.UNAVAILABLE -> stringResource(R.string.linemap_live_unavailable, liveErrorText(live.lastError)) to MaterialTheme.colorScheme.error
    }
    Column {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        if (live.status == LiveStatus.LOADING) {
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
