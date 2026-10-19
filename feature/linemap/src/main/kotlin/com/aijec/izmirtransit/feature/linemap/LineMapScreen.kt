package com.aijec.izmirtransit.feature.linemap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.formatAge
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow

@Composable
fun LineMapRoute(onOpenStop: (stopCode: String) -> Unit, viewModel: LineMapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.liveBuses.collectAsStateWithLifecycle()
    LineMapScreen(
        state = state,
        live = live,
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
        LineCard(
            state = state,
            live = live,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
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
private fun LineCard(state: LineMapUiState, live: LiveSnapshot<List<LiveBus>>, modifier: Modifier = Modifier) {
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
                )
            }
            state.line?.let { line ->
                DirectionLegend(Direction.OUTBOUND, stringResource(R.string.linemap_towards, line.destination))
                DirectionLegend(Direction.RETURN, stringResource(R.string.linemap_towards, line.origin))
            }
            HorizontalDivider()
            LiveStatusLine(live)
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
