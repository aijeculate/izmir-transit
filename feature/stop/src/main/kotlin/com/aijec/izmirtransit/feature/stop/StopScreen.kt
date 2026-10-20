package com.aijec.izmirtransit.feature.stop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.Dot
import com.aijec.izmirtransit.core.ui.EtaText
import com.aijec.izmirtransit.core.ui.FavouriteButton
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.ScreenHeader
import com.aijec.izmirtransit.core.ui.SectionLabel
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.formatAge
import com.aijec.izmirtransit.core.ui.liveErrorText
import com.aijec.izmirtransit.core.ui.rememberNow

@Composable
fun StopRoute(onBack: () -> Unit, onOpenLine: (String) -> Unit, viewModel: StopViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val arrivals by viewModel.arrivals.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    StopScreen(state, arrivals, isFavourite, viewModel::toggleFavourite, onBack, onOpenLine)
}

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
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Calm.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        item("header") {
            ScreenHeader(
                title = state.stop?.name.orEmpty(),
                subtitle = stringResource(R.string.stop_code, state.stopCode),
                onBack = onBack,
                action = { FavouriteButton(isFavourite, onToggleFavourite) },
            )
        }
        if (state.lines.isNotEmpty()) {
            item("lines") { ServingLines(state.lines, onOpenLine) }
        }
        items(state.announcements, key = { "notice-${it.lineCode}-${it.title.hashCode()}" }) { announcement ->
            AnnouncementCard(announcement, showLine = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        item("arrivals-label") {
            SectionLabel(stringResource(R.string.stop_arrivals_title), Modifier.padding(horizontal = 16.dp))
        }
        item("arrivals") { ArrivalsPanel(arrivals, linesByCode, onOpenLine) }
        if (!arrivals.data.isNullOrEmpty()) {
            item("note") {
                Text(
                    stringResource(R.string.stop_eta_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Calm.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ServingLines(lines: List<Line>, onOpenLine: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lines.forEach { line ->
            LineBadge(line.code, Modifier.clickable { onOpenLine(line.code) })
        }
    }
}

@Composable
private fun ArrivalsPanel(arrivals: LiveSnapshot<List<ApproachingBus>>, linesByCode: Map<String, Line>, onOpenLine: (String) -> Unit) {
    val now by rememberNow()
    val age = arrivals.lastUpdated?.let { formatAge(it, now) }.orEmpty()
    val colors = Calm.colors
    val buses = arrivals.data.orEmpty()
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = PaddingValues(vertical = 4.dp)) {
        val status = when (arrivals.status) {
            LiveStatus.LOADING -> stringResource(R.string.stop_live_loading)
            LiveStatus.FRESH -> if (buses.isEmpty()) stringResource(R.string.stop_live_none) else stringResource(R.string.stop_live_updated, age)
            LiveStatus.STALE -> stringResource(R.string.stop_live_stale, liveErrorText(arrivals.lastError), age)
            LiveStatus.UNAVAILABLE -> stringResource(R.string.stop_live_unavailable, liveErrorText(arrivals.lastError))
        }
        val statusColor = if (arrivals.status == LiveStatus.STALE || arrivals.status == LiveStatus.UNAVAILABLE) colors.onWarningSoft else colors.textSecondary
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (arrivals.status == LiveStatus.FRESH && buses.isNotEmpty()) {
                Dot(colors.accent, size = 8.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
        if (arrivals.status == LiveStatus.LOADING) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = colors.accent,
                trackColor = colors.accentSoft,
            )
        }
        buses.forEach { bus ->
            Hairline()
            ArrivalRow(bus, linesByCode[bus.lineCode], onClick = { onOpenLine(bus.lineCode) })
        }
    }
}

@Composable
private fun ArrivalRow(bus: ApproachingBus, line: Line?, onClick: () -> Unit) {
    val colors = Calm.colors
    val towards = when {
        line == null -> bus.lineName
        bus.direction == Direction.OUTBOUND -> line.destination
        else -> line.origin
    }
    val unreliable = bus.eta.basis == EtaBasis.UNRELIABLE
    val etaDescription = if (unreliable) stringResource(R.string.stop_eta_unreliable_description, bus.eta.minutes.toString()) else null
    CalmRow(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        LineBadge(bus.lineCode, strong = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(TransitColors.forDirection(bus.direction), size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.stop_towards, towards),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                pluralStringResource(R.plurals.stop_stops_away, bus.stopsRemaining, bus.stopsRemaining) + features(bus) +
                    (if (unreliable) " · " + stringResource(R.string.stop_eta_unreliable) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        EtaText(
            bus.eta.minutes,
            uncertain = unreliable,
            modifier = if (etaDescription != null) Modifier.semantics { contentDescription = etaDescription } else Modifier,
        )
    }
}

@Composable
private fun features(bus: ApproachingBus): String = buildString {
    if (bus.wheelchairAccessible) append(" · ").append(stringResource(R.string.stop_wheelchair))
    if (bus.bikeRack) append(" · ").append(stringResource(R.string.stop_bike))
}
