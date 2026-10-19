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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveError
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.TransitColors
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun LineMapRoute(viewModel: LineMapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.liveBuses.collectAsStateWithLifecycle()
    LineMapScreen(state, live, onQueryChange = viewModel::onQueryChange, onSelectLine = viewModel::selectLine)
}

@Composable
fun LineMapScreen(
    state: LineMapUiState,
    live: LiveSnapshot<List<LiveBus>>,
    onQueryChange: (String) -> Unit,
    onSelectLine: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            routes = state.routes,
            buses = live.data.orEmpty(),
            contentPadding = MapPadding(start = 32.dp, top = 140.dp, end = 32.dp, bottom = 240.dp),
            modifier = Modifier.fillMaxSize(),
        )
        LineSearch(
            query = state.query,
            results = state.searchResults,
            onQueryChange = onQueryChange,
            onSelectLine = onSelectLine,
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

@Composable
private fun LineSearch(
    query: String,
    results: List<Line>,
    onQueryChange: (String) -> Unit,
    onSelectLine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    Card(modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.linemap_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        if (results.isNotEmpty()) {
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(results, key = { it.code }) { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                focus.clearFocus()
                                onSelectLine(line.code)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LineBadge(line.code)
                        Spacer(Modifier.width(12.dp))
                        Text(line.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (query.isNotBlank()) {
            Text(
                stringResource(R.string.linemap_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
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

@Composable
private fun LineBadge(code: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
        Text(
            code,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Explicit loading, empty, fresh, stale and unavailable states; never a blank. */
@Composable
private fun LiveStatusLine(live: LiveSnapshot<List<LiveBus>>) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val age = live.lastUpdated?.let { formatAge(Duration.between(it, now)) }
    val buses = live.data.orEmpty()
    val (text, color) = when (live.status) {
        LiveStatus.LOADING -> stringResource(R.string.linemap_live_loading) to MaterialTheme.colorScheme.onSurfaceVariant
        LiveStatus.FRESH ->
            if (buses.isEmpty()) stringResource(R.string.linemap_live_none) to MaterialTheme.colorScheme.onSurfaceVariant
            else pluralStringResource(R.plurals.linemap_live_count, buses.size, buses.size, age ?: "") to MaterialTheme.colorScheme.onSurface
        LiveStatus.STALE -> stringResource(R.string.linemap_live_stale, errorText(live.lastError), age ?: "") to MaterialTheme.colorScheme.tertiary
        LiveStatus.UNAVAILABLE -> stringResource(R.string.linemap_live_unavailable, errorText(live.lastError)) to MaterialTheme.colorScheme.error
    }
    Column {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        if (live.status == LiveStatus.LOADING) {
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun errorText(error: LiveError?): String = stringResource(
    when (error) {
        LiveError.RATE_LIMITED -> R.string.linemap_error_busy
        LiveError.SERVER, LiveError.MALFORMED -> R.string.linemap_error_server
        LiveError.NETWORK, null -> R.string.linemap_error_network
    },
)

@Composable
private fun formatAge(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    return if (seconds < 60) stringResource(R.string.linemap_age_seconds, seconds.toInt())
    else stringResource(R.string.linemap_age_minutes, (seconds / 60).toInt())
}

