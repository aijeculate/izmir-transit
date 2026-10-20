package com.aijec.izmirtransit.feature.timetable

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.ui.AnnouncementCard
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.Dot
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.ScreenHeader
import com.aijec.izmirtransit.core.ui.SegmentedControl
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.tabular

@Composable
fun TimetableRoute(onBack: () -> Unit, viewModel: TimetableViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TimetableScreen(state, onBack, viewModel::selectDay, viewModel::selectDirection)
}

@Composable
fun TimetableScreen(
    state: TimetableUiState,
    onBack: () -> Unit,
    onSelectDay: (ServiceDay) -> Unit,
    onSelectDirection: (Direction) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Header items (header, announcements, controls, summary) come before the times.
    val headerCount = 1 + state.announcements.size + 3
    val nextIndex = (state.next as? NextDeparture.At)?.index
    LaunchedEffect(state.serviceDay, state.direction, nextIndex != null) {
        if (nextIndex != null) gridState.animateScrollToItem(headerCount + (nextIndex - 4).coerceAtLeast(0))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 76.dp),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .background(Calm.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fullWidth("header") {
            ScreenHeader(
                title = stringResource(R.string.timetable_title),
                subtitle = state.line?.name,
                onBack = onBack,
                leading = { state.line?.let { LineBadge(it, strong = true) } ?: LineBadge(state.lineCode, strong = true) },
                horizontalPadding = 0.dp,
            )
        }
        state.announcements.forEachIndexed { index, announcement ->
            fullWidth("notice-$index") { AnnouncementCard(announcement, showLine = false) }
        }
        fullWidth("days") {
            SegmentedControl(
                options = ServiceDay.entries,
                selected = state.serviceDay,
                onSelect = onSelectDay,
                label = { day ->
                    stringResource(
                        when (day) {
                            ServiceDay.WEEKDAY -> R.string.timetable_weekday
                            ServiceDay.SATURDAY -> R.string.timetable_saturday
                            ServiceDay.SUNDAY -> R.string.timetable_sunday
                        },
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        fullWidth("directions") { DirectionPicker(state, onSelectDirection) }
        fullWidth("summary") { Summary(state) }
        itemsIndexed(state.departures, key = { _, d -> "${d.direction}-${d.minutesAfterMidnight}-${d.wheelchairAccessible}" }) { index, departure ->
            val next = state.next
            val status = when {
                next == null -> TimeStatus.Normal
                next == NextDeparture.ServiceOver -> TimeStatus.Past
                index < (next as NextDeparture.At).index -> TimeStatus.Past
                index == next.index -> TimeStatus.Next
                else -> TimeStatus.Normal
            }
            TimeCell(TimetableRepository.formatClock(departure.minutesAfterMidnight), status)
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private enum class TimeStatus { Past, Next, Normal }

@Composable
private fun TimeCell(text: String, status: TimeStatus) {
    val colors = Calm.colors
    val nextLabel = stringResource(R.string.timetable_next_label, text)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(Calm.CellShape)
            .background(if (status == TimeStatus.Next) colors.accent else colors.panel)
            .then(if (status == TimeStatus.Next) Modifier.semantics { contentDescription = nextLabel } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = (if (status == TimeStatus.Next) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge).tabular(),
            color = when (status) {
                TimeStatus.Next -> colors.onAccent
                TimeStatus.Past -> colors.textSecondary.copy(alpha = 0.55f)
                TimeStatus.Normal -> colors.text
            },
        )
    }
}

/** The two directions as a segmented control labelled by destination, with the route colour. */
@Composable
private fun DirectionPicker(state: TimetableUiState, onSelect: (Direction) -> Unit) {
    val line = state.line
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedControl(
            options = Direction.entries,
            selected = state.direction,
            onSelect = onSelect,
            label = { direction ->
                val towards = when {
                    line == null -> ""
                    direction == Direction.OUTBOUND -> line.destination
                    else -> line.origin
                }
                stringResource(R.string.timetable_towards, towards)
            },
        )
    }
}

@Composable
private fun Summary(state: TimetableUiState) {
    if (state.loading) return
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth().padding(vertical = 4.dp), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        when {
            state.departures.isEmpty() -> Text(stringResource(R.string.timetable_none), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            else -> when (val next = state.next) {
                is NextDeparture.At -> {
                    Text(stringResource(R.string.timetable_next_title), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            TimetableRepository.formatClock(state.departures[next.index].minutesAfterMidnight),
                            style = MaterialTheme.typography.headlineMedium.tabular(),
                            color = colors.text,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (next.minutesUntil <= 0) stringResource(R.string.timetable_now)
                            else stringResource(R.string.timetable_in_minutes, next.minutesUntil),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }
                NextDeparture.ServiceOver -> Text(stringResource(R.string.timetable_service_over), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                null -> Text(
                    pluralStringResource(R.plurals.timetable_count, state.departures.size, state.departures.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
