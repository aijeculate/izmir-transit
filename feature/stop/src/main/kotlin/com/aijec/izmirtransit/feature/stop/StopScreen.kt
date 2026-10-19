package com.aijec.izmirtransit.feature.stop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.ui.AnnouncementCard
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.formatAge
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow
import com.aijec.izmirtransit.core.ui.R as UiR

@Composable
fun StopRoute(onBack: () -> Unit, onOpenLine: (String) -> Unit, viewModel: StopViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val arrivals by viewModel.arrivals.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    StopScreen(state, arrivals, isFavourite, viewModel::toggleFavourite, onBack, onOpenLine)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(
    state: StopUiState,
    arrivals: LiveSnapshot<List<ApproachingBus>>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    onOpenLine: (String) -> Unit,
) {
    val linesByCode = state.lines.associateBy { it.code }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.stop?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.stop_code, state.stopCode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { FavouriteButton(isFavourite, onToggleFavourite) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.lines.isNotEmpty()) {
                item("lines") { ServingLines(state.lines, onOpenLine) }
                item("lines-divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            items(state.announcements, key = { "notice-${it.lineCode}-${it.title.hashCode()}" }) { announcement ->
                AnnouncementCard(announcement, showLine = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item("status") { ArrivalsStatus(arrivals) }
            items(arrivals.data.orEmpty(), key = { "${it.lineCode}-${it.busId}" }) { bus ->
                ArrivalRow(bus, linesByCode[bus.lineCode], onClick = { onOpenLine(bus.lineCode) })
            }
            if (!arrivals.data.isNullOrEmpty()) {
                item("note") {
                    Text(
                        stringResource(R.string.stop_eta_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServingLines(lines: List<Line>, onOpenLine: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.stop_lines_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                LineBadge(line.code, Modifier.clickable { onOpenLine(line.code) })
            }
        }
    }
}

@Composable
private fun ArrivalsStatus(arrivals: LiveSnapshot<List<ApproachingBus>>) {
    val now by rememberNow()
    val age = arrivals.lastUpdated?.let { formatAge(it, now) }.orEmpty()
    val text = when (arrivals.status) {
        LiveStatus.LOADING -> stringResource(R.string.stop_live_loading)
        LiveStatus.FRESH ->
            if (arrivals.data.isNullOrEmpty()) stringResource(R.string.stop_live_none)
            else stringResource(R.string.stop_live_updated, age)
        LiveStatus.STALE -> stringResource(R.string.stop_live_stale, liveErrorText(arrivals.lastError), age)
        LiveStatus.UNAVAILABLE -> stringResource(R.string.stop_live_unavailable, liveErrorText(arrivals.lastError))
    }
    val color = when (arrivals.status) {
        LiveStatus.STALE -> MaterialTheme.colorScheme.tertiary
        LiveStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.stop_arrivals_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        if (arrivals.status == LiveStatus.LOADING) {
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ArrivalRow(bus: ApproachingBus, line: Line?, onClick: () -> Unit) {
    val towards = when {
        line == null -> bus.lineName
        bus.direction == Direction.OUTBOUND -> line.destination
        else -> line.origin
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(bus.lineCode)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(TransitColors.forDirection(bus.direction), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.stop_towards, towards),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                pluralStringResource(R.plurals.stop_stops_away, bus.stopsRemaining, bus.stopsRemaining) + features(bus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        EtaLabel(bus)
    }
}

@Composable
private fun features(bus: ApproachingBus): String = buildString {
    if (bus.wheelchairAccessible) append(" · ").append(stringResource(R.string.stop_wheelchair))
    if (bus.bikeRack) append(" · ").append(stringResource(R.string.stop_bike))
}

/** ETAs are estimates (the API has none); unreliable ones are marked, not hidden. */
@Composable
private fun EtaLabel(bus: ApproachingBus) {
    val eta = bus.eta
    val unreliable = eta.basis == EtaBasis.UNRELIABLE
    val text = when {
        eta.minutes <= 0 -> stringResource(R.string.stop_eta_now)
        else -> stringResource(R.string.stop_eta_minutes, eta.minutes)
    }
    val description = if (unreliable) stringResource(R.string.stop_eta_unreliable_description, text) else text
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.semantics { contentDescription = description }) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (unreliable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        if (unreliable) {
            Text(stringResource(R.string.stop_eta_unreliable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}


@Composable
private fun FavouriteButton(isFavourite: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = stringResource(if (isFavourite) R.string.stop_favourite_remove else R.string.stop_favourite_add),
            tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
