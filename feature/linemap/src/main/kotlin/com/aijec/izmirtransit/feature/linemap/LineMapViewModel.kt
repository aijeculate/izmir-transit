package com.aijec.izmirtransit.feature.linemap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.MapSelection
import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.location.LocationResult
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ConnectionType
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** Many stops share a name ("Bahribaba" has six), so results carry their lines to tell them apart. */
data class StopResult(val stop: Stop, val lines: List<Line>)

/** The next timetabled departures of a rail or ferry line in one direction, from its first stop. */
data class NextDepartures(val fromStop: String, val minutes: List<Int>)

data class LineMapUiState(
    /** Null on the Map tab before a line is chosen: the all-lines overview. */
    val lineCode: String?,
    /** True when this screen was opened for one line (from Home or a stop), not as the Map tab. */
    val pinned: Boolean = false,
    /** Every route, simplified, for the overview overlay. */
    val overview: List<OverviewRoute> = emptyList(),
    val line: Line? = null,
    val routes: List<LineRoute> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val connections: List<ConnectionType> = emptyList(),
    /** Rail and ferry only: they run to timetable, with no live positions. */
    val nextDepartures: Map<Direction, NextDepartures> = emptyMap(),
    val loadingRoute: Boolean = true,
    val query: String = "",
    val lineResults: List<Line> = emptyList(),
    val stopResults: List<StopResult> = emptyList(),
    /** Set after the user taps "my location". */
    val userLocation: LatLng? = null,
    val nearbyStops: List<Stop> = emptyList(),
    /** Incremented to ask the map to move to [userLocation]. */
    val cameraRequest: Int = 0,
    val locating: Boolean = false,
    val locationProblem: LocationProblem? = null,
) {
    val hasResults: Boolean get() = lineResults.isNotEmpty() || stopResults.isNotEmpty()
}

enum class LocationProblem { PERMISSION, OFF, UNAVAILABLE }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LineMapViewModel @Inject constructor(
    private val lines: LineRepository,
    private val stops: StopRepository,
    live: LiveBusRepository,
    private val favourites: FavouritesRepository,
    private val timetable: TimetableRepository,
    private val clock: Clock,
    private val location: LocationProvider,
    private val savedState: SavedStateHandle,
    private val selection: MapSelection,
    settings: SettingsRepository,
) : ViewModel() {
    val searchAtBottom: StateFlow<Boolean> = settings.settings.map { it.searchAtBottom }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Set when opened for a specific line; absent on the Map tab. */
    private val pinnedLine: String? = savedState[KEY_LINE]
    private val lineCode: StateFlow<String?> =
        if (pinnedLine != null) savedState.getStateFlow<String?>(KEY_LINE, pinnedLine) else selection.line
    private val query = MutableStateFlow("")

    private val _state = MutableStateFlow(LineMapUiState(lineCode.value, pinned = pinnedLine != null))
    val state: StateFlow<LineMapUiState> = _state.asStateFlow()

    /**
     * Live positions for the selected line. WhileSubscribed means polling runs only while
     * the screen collects it (collectAsStateWithLifecycle stops in the background) and
     * survives a rotation.
     */
    val liveBuses: StateFlow<LiveSnapshot<List<LiveBus>>> = lineCode
        .flatMapLatest { code ->
            // Only ESHOT publishes positions; rail and ferry lines show their timetable instead.
            if (code == null || !isBusCode(code)) flowOf(LiveSnapshot(emptyList(), LiveStatus.FRESH)) else live.observeLine(code)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSnapshot(null, LiveStatus.LOADING))

    val isFavourite: StateFlow<Boolean> = combine(lineCode, favourites.favourites) { code, favs -> code != null && favs.hasLine(code) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavourite() {
        val code = lineCode.value ?: return
        viewModelScope.launch { favourites.setLine(code, !isFavourite.value) }
    }

    init {
        viewModelScope.launch {
            lineCode.collectLatest { code ->
                if (code == null) {
                    _state.update {
                        it.copy(
                            lineCode = null, line = null, routes = emptyList(), announcements = emptyList(),
                            connections = emptyList(), nextDepartures = emptyMap(), loadingRoute = false,
                        )
                    }
                    return@collectLatest
                }
                _state.update { it.copy(lineCode = code, nextDepartures = emptyMap(), loadingRoute = true) }
                val line = lines.line(code)
                val routes = lines.routes(code)
                val announcements = timetable.activeAnnouncements(code, TimetableRepository.localNow(clock))
                val connections = lines.connections(code)
                _state.update {
                    it.copy(line = line, routes = routes, announcements = announcements, connections = connections, loadingRoute = false)
                }
                if (!isBusCode(code)) {
                    // Re-read each minute so departed trains drop off; collectLatest cancels this on a new line.
                    while (true) {
                        val departures = nextDepartures(code, routes)
                        _state.update { it.copy(nextDepartures = departures) }
                        delay(60_000L - clock.millis() % 60_000L)
                    }
                }
            }
        }
        viewModelScope.launch {
            val overview = lines.overviewShapes()
            _state.update { it.copy(overview = overview) }
        }
        viewModelScope.launch {
            query.debounce(120).collectLatest { text ->
                val lineResults = if (text.isBlank()) emptyList() else lines.search(text, limit = 15)
                val stopResults = if (text.isBlank()) emptyList() else stops.search(text, limit = 15).map { stop ->
                    StopResult(stop, stops.linesAtStop(stop.code))
                }
                _state.update { it.copy(lineResults = lineResults, stopResults = stopResults) }
            }
        }
    }

    private suspend fun nextDepartures(code: String, routes: List<LineRoute>): Map<Direction, NextDepartures> {
        val moment = TimetableRepository.serviceMoment(TimetableRepository.localNow(clock))
        return routes.mapNotNull { route ->
            val first = route.stops.firstOrNull() ?: return@mapNotNull null
            val minutes = stops.scheduledDepartures(first.code, moment.serviceDay, moment.minutesAfterMidnight, limit = 60)
                .filter { it.line.code == code && it.direction == route.direction }
                .map { it.minutesAfterMidnight }
                .distinct()
                .take(NEXT_DEPARTURES)
            route.direction to NextDepartures(first.name, minutes)
        }.toMap()
    }

    fun onQueryChange(text: String) {
        query.value = text
        _state.update {
            if (text.isBlank()) it.copy(query = text, lineResults = emptyList(), stopResults = emptyList()) else it.copy(query = text)
        }
    }

    fun hasLocationPermission(): Boolean = location.hasPermission()

    /** Centres the map on the user and shows the stops around them. */
    fun locate() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, locationProblem = null) }
        viewModelScope.launch {
            when (val result = location.current()) {
                is LocationResult.Found -> {
                    val nearby = stops.nearby(result.location, radiusMeters = NEARBY_RADIUS_METERS, limit = NEARBY_LIMIT).map { it.stop }
                    _state.update {
                        it.copy(userLocation = result.location, nearbyStops = nearby, cameraRequest = it.cameraRequest + 1, locating = false)
                    }
                }
                LocationResult.PermissionMissing -> _state.update { it.copy(locating = false, locationProblem = LocationProblem.PERMISSION) }
                LocationResult.LocationOff -> _state.update { it.copy(locating = false, locationProblem = LocationProblem.OFF) }
                LocationResult.Unavailable -> _state.update { it.copy(locating = false, locationProblem = LocationProblem.UNAVAILABLE) }
            }
        }
    }

    fun onLocationPermissionDenied() = _state.update { it.copy(locationProblem = LocationProblem.PERMISSION) }

    fun consumeLocationProblem() = _state.update { it.copy(locationProblem = null) }

    fun selectLine(code: String) {
        if (pinnedLine != null) savedState[KEY_LINE] = code else selection.select(code)
        onQueryChange("")
    }

    /** Back to the all-lines overview (Map tab only). */
    fun clearLine() {
        if (pinnedLine == null) selection.select(null)
    }

    companion object {
        /** Navigation argument; also where the selected line survives process death. */
        const val KEY_LINE = "lineCode"

        private const val NEARBY_RADIUS_METERS = 800.0
        private const val NEARBY_LIMIT = 40
        private const val NEXT_DEPARTURES = 3
    }
}
