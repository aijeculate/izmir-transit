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
    lines: LineRepository,
    private val timetable: TimetableRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val lineCode: String = checkNotNull(savedState[LINE_CODE_ARG]) { "lineCode argument missing" }

    private val header = MutableStateFlow(TimetableUiState(lineCode))
    private val selection = MutableStateFlow(
        TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)).serviceDay to Direction.OUTBOUND,
    )

    /** Re-evaluates "next departure" every 30 s while the screen is open. */
    private val minuteTicker = flow {
        while (true) {
            emit(TimetableRepository.serviceMoment(TimetableRepository.localNow(clock)))
            delay(30_000)
        }
    }

    private val departures = selection.mapLatest { (day, direction) ->
        Triple(day, direction, timetable.departures(lineCode, day, direction))
    }

    val state: StateFlow<TimetableUiState> = combine(header, departures, minuteTicker) { head, (day, direction, list), moment ->
        val next = if (moment.serviceDay != day) {
            null
        } else {
            val index = TimetableRepository.nextDepartureIndex(list, moment.minutesAfterMidnight)
            if (index < 0) NextDeparture.ServiceOver
            else NextDeparture.At(index, list[index].minutesAfterMidnight - moment.minutesAfterMidnight)
        }
        head.copy(serviceDay = day, direction = direction, departures = list, loading = false, next = next)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableUiState(lineCode))

    init {
        viewModelScope.launch {
            val line = lines.line(lineCode)
            val announcements = timetable.activeAnnouncements(lineCode, TimetableRepository.localNow(clock))
            header.update { it.copy(line = line, announcements = announcements) }
        }
    }

    fun selectDay(day: ServiceDay) = selection.update { it.copy(first = day) }

    fun selectDirection(direction: Direction) = selection.update { it.copy(second = direction) }

    companion object {
        const val LINE_CODE_ARG = "lineCode"
    }
}
