package com.aijec.izmirtransit.feature.stop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.Stop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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

@HiltViewModel
class StopViewModel @Inject constructor(
    stops: StopRepository,
    live: LiveBusRepository,
    private val favourites: FavouritesRepository,
    private val timetable: TimetableRepository,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val stopCode: String = checkNotNull(savedState[STOP_CODE_ARG]) { "stopCode argument missing" }

    private val _state = MutableStateFlow(StopUiState(stopCode))
    val state: StateFlow<StopUiState> = _state.asStateFlow()

    /** Polls only while the screen is visible; see LineMapViewModel. */
    val arrivals: StateFlow<LiveSnapshot<List<ApproachingBus>>> = live.observeStop(stopCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSnapshot(null, LiveStatus.LOADING))

    val isFavourite: StateFlow<Boolean> = favourites.favourites.map { it.hasStop(stopCode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavourite() {
        viewModelScope.launch { favourites.setStop(stopCode, !isFavourite.value) }
    }

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
    }
}
