package com.aijec.izmirtransit.feature.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import com.aijec.izmirtransit.core.data.routing.TransitNetwork
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.Departure
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import kotlin.math.roundToInt

data class TimetableUiState(
    val lineCode: String,
    val line: Line? = null,
    val announcements: List<Announcement> = emptyList(),
    val serviceDay: ServiceDay = ServiceDay.WEEKDAY,
    val direction: Direction = Direction.OUTBOUND,
    val departures: List<Departure> = emptyList(),
    /** Rail and ferry: the stations of the selected direction, and the one the times are for. */
    val stations: List<Stop> = emptyList(),
    /** Loop lines (Çiğli tram, the Ring) run one way only. */
    val directions: List<Direction> = Direction.entries,
    /**
     * A ring line shown as one round trip: its starting direction, the only timetable. Null
     * when ring lines are shown one direction at a time (a setting).
     */
    val ringStart: Direction? = null,
    /**
     * A ring line's way back, shown on its own: the times are estimated as the start's
     * departures plus this many minutes of driving, the timetable having none.
     */
    val estimatedAfter: Int? = null,
    val station: Stop? = null,
    val loading: Boolean = true,
    /** Set only when the selected day is the timetable running now. */
    val next: NextDeparture? = null,
)

sealed interface NextDeparture {
    data class At(val index: Int, val minutesUntil: Int) : NextDeparture
    data object ServiceOver : NextDeparture
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val lines: LineRepository,
    private val timetable: TimetableRepository,
    private val clock: Clock,
    settings: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val lineCode: String = checkNotNull(savedState[LINE_CODE_ARG]) { "lineCode argument missing" }

    /** The stop the user came from: its station and direction are shown first. */
    private val fromStop: String? = savedState[STOP_ARG]

    /** The direction the line map was showing when the timetable was opened, if any. */
    private val requestedDirection: Direction? =
        savedState.get<String>(DIRECTION_ARG)?.let { name -> Direction.entries.firstOrNull { it.name == name } }

    private val header = MutableStateFlow(TimetableUiState(lineCode))

    private data class Selection(val day: ServiceDay, val direction: Direction, val stationCode: String? = null)
    private val selection = MutableStateFlow(
        Selection(
            TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)).serviceDay,
            requestedDirection ?: Direction.OUTBOUND,
            fromStop,
        ),
    )

    /** Buses only have times from their first stop; rail and ferry have every station's. */
    private val byStation = !isBusCode(lineCode)
    private val routes = flow { emit(if (byStation) lines.routes(lineCode) else emptyList()) }

    /** Ring lines are timed from one end only: that direction is the whole timetable. */
    private val ringStart = flow { emit(lines.line(lineCode)?.let { timetable.ringStart(it) }) }
    private val ringRoundTrip = settings.settings.map { it.ringAsRoundTrip }

    /** Minutes a ring line takes to drive its way out, for estimating the way back. */
    private suspend fun ringRunningMinutes(start: Direction): Int {
        val shape = lines.routes(lineCode).firstOrNull { it.direction == start }?.shape.orEmpty()
        return (MeasuredPolyline(shape).lengthMeters / TransitNetwork.BUS_METERS_PER_MINUTE).roundToInt()
    }

    /** Re-evaluates "next departure" every 30 s while the screen is open. */
    private val minuteTicker = flow {
        while (true) {
            emit(TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)))
            delay(30_000)
        }
    }

    private data class Loaded(
        val day: ServiceDay,
        val direction: Direction,
        val stations: List<Stop>,
        val station: Stop?,
        val list: List<Departure>,
        val directions: List<Direction>,
        val ringStart: Direction?,
        val estimatedAfter: Int?,
    )

    private data class Inputs(val chosen: Selection, val routes: List<com.aijec.izmirtransit.core.model.LineRoute>, val ring: Direction?, val roundTrip: Boolean)

    private val departures = combine(selection, routes, ringStart, ringRoundTrip) { chosen, routes, ring, roundTrip -> Inputs(chosen, routes, ring, roundTrip) }.mapLatest { (chosen, routes, ringLine, roundTrip) ->
        val ring = ringLine?.takeIf { roundTrip }
        // A loop line runs one way only: a direction it doesn't have falls back to the one it does.
        val selected = when {
            ring != null -> chosen.copy(direction = ring)
            routes.isNotEmpty() && routes.none { it.direction == chosen.direction } -> chosen.copy(direction = routes.first().direction)
            else -> chosen
        }
        val stations = routes.firstOrNull { it.direction == selected.direction }?.stops.orEmpty()
            // The terminus has no departures in this direction.
            .dropLast(1)
        val station = stations.firstOrNull { it.code == selected.stationCode } ?: stations.firstOrNull()
        // A ring line's way back has no times of its own: the way out's, plus the drive.
        val estimatedAfter = if (ringLine != null && ring == null && station == null && selected.direction != ringLine) {
            ringRunningMinutes(ringLine)
        } else {
            null
        }
        val list = when {
            station != null -> timetable.departuresAt(station.code, lineCode, selected.day, selected.direction)
            estimatedAfter != null -> timetable.departures(lineCode, selected.day, ringLine!!).map {
                it.copy(direction = selected.direction, minutesAfterMidnight = it.minutesAfterMidnight + estimatedAfter)
            }
            else -> timetable.departures(lineCode, selected.day, selected.direction)
        }
        val directions = when {
            ring != null -> listOf(ring)
            routes.isEmpty() -> Direction.entries
            else -> routes.map { it.direction }.distinct().sorted()
        }
        Loaded(selected.day, selected.direction, stations, station, list, directions, ring, estimatedAfter)
    }

    val state: StateFlow<TimetableUiState> = combine(header, departures, minuteTicker) { head, loaded, moment ->
        val (day, direction, stations, station, list) = loaded
        val next = if (moment.serviceDay != day) {
            null
        } else {
            val index = TimetableRepository.nextDepartureIndex(list, moment.minutesAfterMidnight)
            if (index < 0) NextDeparture.ServiceOver
            else NextDeparture.At(index, list[index].minutesAfterMidnight - moment.minutesAfterMidnight)
        }
        head.copy(
            serviceDay = day,
            direction = direction,
            departures = list,
            stations = stations,
            station = station,
            directions = loaded.directions,
            ringStart = loaded.ringStart,
            estimatedAfter = loaded.estimatedAfter,
            loading = false,
            next = next,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableUiState(lineCode))

    init {
        // The direction shown on the line map wins; otherwise start in the one leaving that stop.
        if (fromStop != null && requestedDirection == null) {
            viewModelScope.launch {
                // Start in the direction that leaves from that stop.
                val direction = lines.routes(lineCode).firstOrNull { r -> r.stops.dropLast(1).any { it.code == fromStop } }?.direction
                if (direction != null) selection.update { it.copy(direction = direction) }
            }
        }
        viewModelScope.launch {
            val line = lines.line(lineCode)
            val announcements = timetable.activeAnnouncements(lineCode, TimetableRepository.localNow(clock))
            header.update { it.copy(line = line, announcements = announcements) }
        }
    }

    fun selectDay(day: ServiceDay) = selection.update { it.copy(day = day) }

    /** Keeps the chosen station: it is on both directions' lists (except at a terminus). */
    fun selectDirection(direction: Direction) = selection.update { it.copy(direction = direction) }

    fun selectStation(code: String) = selection.update { it.copy(stationCode = code) }

    companion object {
        const val LINE_CODE_ARG = "lineCode"
        const val STOP_ARG = "stop"
        const val DIRECTION_ARG = "direction"
    }
}
