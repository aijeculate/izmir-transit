package com.aijec.izmirtransit.feature.planner

import com.aijec.izmirtransit.core.ui.ModeIcon
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.map.MapPadding
import com.aijec.izmirtransit.core.map.TransitMap
import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.CalmRow
import com.aijec.izmirtransit.core.ui.CircleIconButton
import com.aijec.izmirtransit.core.ui.Dot
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.LineBadge
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.PillSearchField
import com.aijec.izmirtransit.core.ui.SegmentedControl
import com.aijec.izmirtransit.core.ui.TransitColors
import com.aijec.izmirtransit.core.ui.stopKindLabel
import com.aijec.izmirtransit.core.ui.tabular

@Composable
fun PlannerRoute(onBack: () -> Unit, onOpenStop: (String) -> Unit, viewModel: PlannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.useMyLocation()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        when (message) {
            PlannerMessage.LOCATION_PERMISSION -> Unit // shown in the panel, with a button
            else -> {
                Toast.makeText(context, messageText(message), Toast.LENGTH_LONG).show()
                viewModel.consumeMessage()
            }
        }
    }
    PlannerScreen(
        state = state,
        onBack = onBack,
        onEdit = viewModel::edit,
        onQueryChange = viewModel::onQueryChange,
        onChoose = viewModel::choose,
        onSwap = viewModel::swap,
        onDepartIn = viewModel::setDepartIn,
        onSelect = viewModel::select,
        onAskLocation = { viewModel.consumeMessage(); permission.launch(LocationProvider.PERMISSIONS) },
        onOpenStop = onOpenStop,
    )
}

private fun messageText(message: PlannerMessage): Int = when (message) {
    PlannerMessage.LOCATION_PERMISSION -> R.string.planner_location_permission
    PlannerMessage.LOCATION_OFF -> R.string.planner_location_off
    PlannerMessage.LOCATION_UNAVAILABLE -> R.string.planner_location_unavailable
    PlannerMessage.NO_ROUTE -> R.string.planner_no_route
    PlannerMessage.PLACE_FAILED -> R.string.planner_place_failed
}

private val DEPART_OPTIONS = listOf(0, 15, 30, 60)

@Composable
fun PlannerScreen(
    state: PlannerUiState,
    onBack: () -> Unit,
    onEdit: (Field?) -> Unit,
    onQueryChange: (String) -> Unit,
    onChoose: (Suggestion) -> Unit,
    onSwap: () -> Unit,
    onDepartIn: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onAskLocation: () -> Unit,
    onOpenStop: (String) -> Unit,
) {
    val journey = state.selectedJourney
    val from = state.from
    val to = state.to
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            contentPadding = MapPadding(start = 24.dp, top = 230.dp, end = 24.dp, bottom = 360.dp),
            plan = if (journey != null && from != null && to != null) PlannerViewModel.mapPlan(journey, from.location, to.location) else null,
            pin = if (journey == null) to?.location else null,
            myLocationEnabled = from?.isMyLocation == true,
            onStopClick = { onOpenStop(it.code) },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EndpointsPanel(state, onBack, onEdit, onSwap)
            if (state.editing != null) {
                SearchPanel(state, onQueryChange, onChoose, Modifier.imePadding())
            }
        }
        if (state.editing == null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResultsPanel(state, onDepartIn, onSelect, onAskLocation)
            }
        }
    }
}

@Composable
private fun EndpointsPanel(state: PlannerUiState, onBack: () -> Unit, onEdit: (Field?) -> Unit, onSwap: () -> Unit) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth(), glass = true, padding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.planner_back), onClick = onBack, plain = true)
            Column(Modifier.weight(1f)) {
                EndpointRow(
                    label = stringResource(R.string.planner_from),
                    value = state.from?.let { if (it.isMyLocation) stringResource(R.string.planner_my_location) else it.name },
                    placeholder = if (state.locating) stringResource(R.string.planner_locating) else stringResource(R.string.planner_choose_start),
                    dot = colors.accent,
                    active = state.editing == Field.FROM,
                    onClick = { onEdit(Field.FROM) },
                )
                Hairline(inset = 0.dp)
                EndpointRow(
                    label = stringResource(R.string.planner_to),
                    value = state.to?.name?.ifBlank { stringResource(R.string.planner_dropped_pin) },
                    placeholder = stringResource(R.string.planner_where_to),
                    dot = Color(0xFFE53935),
                    active = state.editing == Field.TO,
                    onClick = { onEdit(Field.TO) },
                )
            }
            CircleIconButton(Icons.Filled.SwapVert, stringResource(R.string.planner_swap), onClick = onSwap, tint = colors.textSecondary, plain = true)
        }
    }
}

@Composable
private fun EndpointRow(label: String, value: String?, placeholder: String, dot: Color, active: Boolean, onClick: () -> Unit) {
    val colors = Calm.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot, size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value == null || active) colors.textSecondary else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchPanel(state: PlannerUiState, onQueryChange: (String) -> Unit, onChoose: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    val colors = Calm.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.editing) { runCatching { focus.requestFocus() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(if (state.placesEnabled) R.string.planner_search_hint else R.string.planner_search_hint_stops),
            glass = true,
            modifier = Modifier.focusRequester(focus),
        )
        if (state.suggestions.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth(), glass = true, padding = PaddingValues(vertical = 6.dp)) {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(state.suggestions, key = { suggestionKey(it) }) { suggestion ->
                        CalmRow(onClick = { onChoose(suggestion) }, padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            val (icon, title, subtitle) = when (suggestion) {
                                Suggestion.MyLocation -> Triple(Icons.Filled.MyLocation, stringResource(R.string.planner_my_location), "")
                                is Suggestion.Place -> Triple(Icons.Filled.Place, suggestion.place.title, suggestion.place.subtitle)
                                is Suggestion.StopResult -> Triple(null, suggestion.stop.name, stopKindLabel(suggestion.stop))
                            }
                            if (suggestion is Suggestion.StopResult) {
                                ModeIcon(suggestion.stop.mode, size = 28.dp)
                            } else {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    Icon(icon!!, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (subtitle.isNotEmpty()) {
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun suggestionKey(s: Suggestion): String = when (s) {
    Suggestion.MyLocation -> "me"
    is Suggestion.Place -> "place-${s.place.placeId}"
    is Suggestion.StopResult -> "stop-${s.stop.code}"
}

@Composable
private fun ResultsPanel(state: PlannerUiState, onDepartIn: (Int) -> Unit, onSelect: (Int) -> Unit, onAskLocation: () -> Unit) {
    val colors = Calm.colors
    Panel(Modifier.fillMaxWidth().animateContentSize(), glass = true, padding = PaddingValues(vertical = 10.dp)) {
        SegmentedControl(
            options = DEPART_OPTIONS,
            selected = state.departInMinutes,
            onSelect = onDepartIn,
            label = { if (it == 0) stringResource(R.string.planner_now) else stringResource(R.string.planner_in_minutes, it) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        when {
            state.message == PlannerMessage.LOCATION_PERMISSION && state.from == null -> {
                Text(
                    stringResource(R.string.planner_location_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                com.aijec.izmirtransit.core.ui.PillButton(
                    stringResource(R.string.planner_allow_location),
                    onClick = onAskLocation,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            state.planning -> {
                Text(stringResource(R.string.planner_planning), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = colors.accent, trackColor = colors.accentSoft)
            }
            state.journeys.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 6.dp)) {
                if (state.nextDay) {
                    item("next-day") {
                        Text(
                            stringResource(R.string.planner_next_day),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onWarningSoft,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                itemsIndexed(state.journeys) { index, journey ->
                    if (index > 0) Hairline()
                    JourneyCard(journey, expanded = index == state.selected, onClick = { onSelect(index) })
                }
            }
            state.from == null || state.to == null -> Text(
                stringResource(R.string.planner_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            else -> Text(stringResource(R.string.planner_no_route), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun JourneyCard(journey: Journey, expanded: Boolean, onClick: () -> Unit) {
    val colors = Calm.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clock(journey.departureMinute)} – ${clock(journey.arrivalMinute)}",
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.planner_minutes, journey.durationMinutes),
                style = MaterialTheme.typography.titleSmall.tabular(),
                color = if (expanded) colors.accent else colors.textSecondary,
            )
        }
        Spacer(Modifier.size(6.dp))
        // The chain of lines: walk › 5 › M1 › İZBAN.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (journey.rides.isEmpty()) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.planner_walk_only), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            journey.rides.forEachIndexed { i, ride ->
                if (i > 0) Text("›", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                LineBadge(ride.line, strong = true)
            }
        }
        val changes = (journey.rides.size - 1).coerceAtLeast(0)
        Text(
            if (changes == 0) stringResource(R.string.planner_summary_direct, journey.walkingMeters)
            else pluralStringResource(R.plurals.planner_summary, changes, changes, journey.walkingMeters),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (expanded) {
            Spacer(Modifier.size(8.dp))
            journey.legs.forEach { leg -> LegRow(leg) }
        }
    }
}

@Composable
private fun LegRow(leg: JourneyLeg) {
    val colors = Calm.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            clock(leg.startMinute),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = colors.textSecondary,
            modifier = Modifier.width(46.dp).padding(top = 2.dp),
        )
        when (leg) {
            is JourneyLeg.Walk -> {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val target = leg.toStop?.name ?: stringResource(R.string.planner_destination)
                Text(
                    stringResource(R.string.planner_walk_to, target, leg.meters, leg.endMinute - leg.startMinute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            is JourneyLeg.Ride -> {
                ModeIcon(leg.line.mode, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(leg.line, strong = true)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.planner_towards, leg.headsign),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        stringResource(R.string.planner_board, leg.board.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        pluralStringResource(R.plurals.planner_alight, leg.stops.size - 1, leg.stops.size - 1, leg.alight.name, clock(leg.endMinute)) +
                            if (leg.exactTimes) "" else " · " + stringResource(R.string.planner_estimated),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

private fun clock(minute: Int) = TimetableRepository.formatClock(minute)
