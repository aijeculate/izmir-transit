package com.aijec.izmirtransit.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.ui.AnnouncementCard
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.R as UiR

@Composable
fun TimetableRoute(onBack: () -> Unit, viewModel: TimetableViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TimetableScreen(state, onBack, viewModel::selectDay, viewModel::selectDirection)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    onBack: () -> Unit,
    onSelectDay: (ServiceDay) -> Unit,
    onSelectDirection: (Direction) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Header items (announcements, tabs, chips, summary) come before the times.
    val headerCount = state.announcements.size + 3
    val nextIndex = (state.next as? NextDeparture.At)?.index
    LaunchedEffect(state.serviceDay, state.direction, nextIndex != null) {
        if (nextIndex != null) gridState.animateScrollToItem(headerCount + (nextIndex - 4).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(state.lineCode)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.timetable_title), maxLines = 1)
                            Text(
                                state.line?.name.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 76.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.announcements.forEach { announcement ->
                item(span = { GridItemSpan(maxLineSpan) }) { AnnouncementCard(announcement, showLine = false) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { DayTabs(state.serviceDay, onSelectDay) }
            item(span = { GridItemSpan(maxLineSpan) }) { DirectionChips(state, onSelectDirection) }
            item(span = { GridItemSpan(maxLineSpan) }) { Summary(state) }
            itemsIndexed(state.departures, key = { _, d -> "${d.direction}-${d.minutesAfterMidnight}-${d.wheelchairAccessible}" }) { index, departure ->
                val next = state.next
                val status = when {
                    next !is NextDeparture.At && next != NextDeparture.ServiceOver -> TimeStatus.Normal
                    next == NextDeparture.ServiceOver -> TimeStatus.Past
                    index < (next as NextDeparture.At).index -> TimeStatus.Past
                    index == next.index -> TimeStatus.Next
                    else -> TimeStatus.Normal
                }
                TimeCell(TimetableRepository.formatClock(departure.minutesAfterMidnight), status)
            }
        }
    }
}

private enum class TimeStatus { Past, Next, Normal }

@Composable
private fun TimeCell(text: String, status: TimeStatus) {
    val nextLabel = stringResource(R.string.timetable_next_label, text)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when (status) {
            TimeStatus.Next -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = if (status == TimeStatus.Next) Modifier.semantics { contentDescription = nextLabel } else Modifier,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (status == TimeStatus.Next) FontWeight.Bold else FontWeight.Normal,
                color = when (status) {
                    TimeStatus.Next -> MaterialTheme.colorScheme.onPrimary
                    TimeStatus.Past -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    TimeStatus.Normal -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun DayTabs(selected: ServiceDay, onSelect: (ServiceDay) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        ServiceDay.entries.forEach { day ->
            Tab(
                selected = day == selected,
                onClick = { onSelect(day) },
                text = {
                    Text(
                        stringResource(
                            when (day) {
                                ServiceDay.WEEKDAY -> R.string.timetable_weekday
                                ServiceDay.SATURDAY -> R.string.timetable_saturday
                                ServiceDay.SUNDAY -> R.string.timetable_sunday
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun DirectionChips(state: TimetableUiState, onSelect: (Direction) -> Unit) {
    val line = state.line
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Direction.entries.forEach { direction ->
            val towards = when {
                line == null -> ""
                direction == Direction.OUTBOUND -> line.destination
                else -> line.origin
            }
            FilterChip(
                selected = state.direction == direction,
                onClick = { onSelect(direction) },
                label = {
                    Text(stringResource(R.string.timetable_towards, towards), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = {
                    Box(Modifier.size(10.dp).background(TransitColors.forDirection(direction), CircleShape))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Summary(state: TimetableUiState) {
    val text = when {
        state.loading -> ""
        state.departures.isEmpty() -> stringResource(R.string.timetable_none)
        else -> when (val next = state.next) {
            is NextDeparture.At ->
                if (next.minutesUntil <= 0) stringResource(R.string.timetable_next_now, TimetableRepository.formatClock(state.departures[next.index].minutesAfterMidnight))
                else stringResource(R.string.timetable_next_in, TimetableRepository.formatClock(state.departures[next.index].minutesAfterMidnight), next.minutesUntil)
            NextDeparture.ServiceOver -> stringResource(R.string.timetable_service_over)
            null -> pluralStringResource(R.plurals.timetable_count, state.departures.size, state.departures.size)
        }
    }
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
}
