package com.aijec.izmirtransit.feature.stop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.alerts.AlertRequest
import com.aijec.izmirtransit.core.data.alerts.ArrivalAlerts
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.ScheduledDeparture
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class StopUiState(
    val stopCode: String,
    val stop: Stop? = null,
    /** Lines serving this stop, from the offline data. */
    val lines: List<Line> = emptyList(),
    /** Active notices for any line serving this stop. */
    val announcements: List<Announcement> = emptyList(),
    val loading: Boolean = true,
)

/** Departures from [nowMinute] on, on the service day's clock (may exceed 1440). */
data class StationBoard(val nowMinute: Int, val departures: List<ScheduledDeparture>)

@HiltViewModel
class StopViewModel @Inject constructor(
    private val stops: StopRepository,
    live: LiveBusRepository,
    private val favourites: FavouritesRepository,
    private val timetable: TimetableRepository,
    private val clock: Clock,
    private val alerts: ArrivalAlerts,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val stopCode: String = checkNotNull(savedState[STOP_CODE_ARG]) { "stopCode argument missing" }

    private val _state = MutableStateFlow(StopUiState(stopCode))
    val state: StateFlow<StopUiState> = _state.asStateFlow()

    /** Stations and piers run to timetable; only bus stops have live arrivals. */
    val isBusStop: Boolean = isBusCode(stopCode)

    /** Polls only while the screen is visible; see LineMapViewModel. */
    val arrivals: StateFlow<LiveSnapshot<List<ApproachingBus>>> =
        (if (isBusStop) live.observeStop(stopCode) else flowOf(LiveSnapshot(emptyList(), LiveStatus.FRESH)))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSnapshot(null, LiveStatus.LOADING))

    /**
     * The station board: the next timetabled departures, re-read at each minute so passed
     * trains drop off. Null while loading; always empty for bus stops.
     */
    val departures: StateFlow<StationBoard?> = flow {
        if (!isBusStop) {
            while (true) {
                val moment = TimetableRepository.serviceMoment(TimetableRepository.localNow(clock))
                emit(StationBoard(moment.minutesAfterMidnight, stops.scheduledDepartures(stopCode, moment.serviceDay, moment.minutesAfterMidnight, BOARD_SIZE)))
                delay(60_000L - clock.millis() % 60_000L)
            }
        } else {
            emit(StationBoard(0, emptyList()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavourite: StateFlow<Boolean> = favourites.favourites.map { it.hasStop(stopCode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavourite() {
        viewModelScope.launch { favourites.setStop(stopCode, !isFavourite.value) }
    }

    /** The arrival alert currently running, if any (one at a time, app-wide). */
    val activeAlert: StateFlow<AlertRequest?> = alerts.active

    fun startAlert(bus: ApproachingBus, alertAtStops: Int) {
        alerts.start(
            AlertRequest(
                busId = bus.busId,
                lineCode = bus.lineCode,
                stopCode = stopCode,
                stopName = _state.value.stop?.name ?: stopCode,
                alertAtStops = alertAtStops,
            ),
        )
    }

    fun stopAlert() = alerts.stop()

    init {
        viewModelScope.launch {
            val stop = stops.stop(stopCode)
            val lines = stops.linesAtStop(stopCode)
            val announcements = timetable.activeAnnouncementsForLines(lines.map { it.code }, TimetableRepository.localNow(clock))
            _state.value = StopUiState(stopCode, stop, lines, announcements, loading = false)
        }
    }

    companion object {
        const val STOP_CODE_ARG = "stopCode"
        private const val BOARD_SIZE = 40
    }
}
