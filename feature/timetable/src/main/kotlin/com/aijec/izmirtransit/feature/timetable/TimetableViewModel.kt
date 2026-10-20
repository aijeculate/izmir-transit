package com.aijec.izmirtransit.feature.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.Departure
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class TimetableUiState(
    val lineCode: String,
    val line: Line? = null,
    val announcements: List<Announcement> = emptyList(),
    val serviceDay: ServiceDay = ServiceDay.WEEKDAY,
    val direction: Direction = Direction.OUTBOUND,
    val departures: List<Departure> = emptyList(),
    /** Rail and ferry: the stations of the selected direction, and the one the times are for. */
    val stations: List<Stop> = emptyList(),
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
    savedState: SavedStateHandle,
) : ViewModel() {
    private val lineCode: String = checkNotNull(savedState[LINE_CODE_ARG]) { "lineCode argument missing" }

    private val header = MutableStateFlow(TimetableUiState(lineCode))

    private data class Selection(val day: ServiceDay, val direction: Direction, val stationCode: String? = null)
    private val selection = MutableStateFlow(
        Selection(TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)).serviceDay, Direction.OUTBOUND),
    )

    /** Buses only have times from their first stop; rail and ferry have every station's. */
    private val byStation = !isBusCode(lineCode)
    private val routes = flow { emit(if (byStation) lines.routes(lineCode) else emptyList()) }

    /** Re-evaluates "next departure" every 30 s while the screen is open. */
    private val minuteTicker = flow {
        while (true) {
            emit(TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)))
            delay(30_000)
        }
    }

    private data class Loaded(val day: ServiceDay, val direction: Direction, val stations: List<Stop>, val station: Stop?, val list: List<Departure>)

    private val departures = combine(selection, routes) { selected, routes -> selected to routes }.mapLatest { (selected, routes) ->
        val stations = routes.firstOrNull { it.direction == selected.direction }?.stops.orEmpty()
            // The terminus has no departures in this direction.
            .dropLast(1)
        val station = stations.firstOrNull { it.code == selected.stationCode } ?: stations.firstOrNull()
        val list = if (station != null) timetable.departuresAt(station.code, lineCode, selected.day, selected.direction)
        else timetable.departures(lineCode, selected.day, selected.direction)
        Loaded(selected.day, selected.direction, stations, station, list)
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
        head.copy(serviceDay = day, direction = direction, departures = list, stations = stations, station = station, loading = false, next = next)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableUiState(lineCode))

    init {
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
    }
}
