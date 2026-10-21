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
import com.aijec.izmirtransit.core.map.CameraRequest
import com.aijec.izmirtransit.core.map.Viewport
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.MapLayer
import com.aijec.izmirtransit.core.model.OverviewRoute
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.isBusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

/** A tapped stop, shown in a card over the map before opening it. */
data class StopPreview(val stop: Stop, val lines: List<Line>, val transfers: List<Stop> = emptyList())

/** The next timetabled departures of a rail or ferry line in one direction, from its first stop. */
data class NextDepartures(val fromStop: String, val minutes: List<Int>)

data class LineMapUiState(
    /** Null on the Map tab before a line is chosen: the all-lines overview. */
    val lineCode: String?,
    /** True when this screen was opened for one line (from Home or a stop), not as the Map tab. */
    val pinned: Boolean = false,
    val line: Line? = null,
    val routes: List<LineRoute> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val connections: List<ConnectionType> = emptyList(),
    /** The direction shown when only one is drawn (the default). */
    val direction: Direction = Direction.OUTBOUND,
    /** Rail and ferry only: they run to timetable, with no live positions. */
    val nextDepartures: Map<Direction, NextDepartures> = emptyMap(),
    val loadingRoute: Boolean = true,
    val query: String = "",
    val lineResults: List<Line> = emptyList(),
    val stopResults: List<StopResult> = emptyList(),
    /** Set after the user taps "my location". */
    val userLocation: LatLng? = null,
    /** Asks the map to move, e.g. to the user after "my location". */
    val cameraRequest: CameraRequest? = null,
    /** Shows the blue location dot once location permission is granted. */
    val locationEnabled: Boolean = false,
    /** Every stop in view when zoomed in far enough (and the stops layer is on). */
    val areaStops: List<Stop> = emptyList(),
    val preview: StopPreview? = null,
    /** A point long-pressed on the map, offered as a trip destination. */
    val pin: LatLng? = null,
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val settings get() = settingsRepository
    val searchAtBottom: StateFlow<Boolean> = settings.settings.map { it.searchAtBottom }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Set when opened for a specific line; absent on the Map tab. */
    private val pinnedLine: String? = savedState[KEY_LINE]

    /** The stop this line was opened from: its direction is shown first, its timetable opened. */
    val fromStop: String? = savedState[KEY_FROM_STOP]
    private val lineCode: StateFlow<String?> =
        if (pinnedLine != null) savedState.getStateFlow<String?>(KEY_LINE, pinnedLine) else selection.line
    private val query = MutableStateFlow("")

    private val _state = MutableStateFlow(
        LineMapUiState(lineCode.value, pinned = pinnedLine != null, locationEnabled = location.hasPermission()),
    )
    val state: StateFlow<LineMapUiState> = _state.asStateFlow()

    /** Draw both directions at once (a setting); otherwise one, with a switch. */
    val showBothDirections: StateFlow<Boolean> = settings.settings.map { it.showBothDirections }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun switchDirection() = _state.update { s ->
        val available = s.routes.map { it.direction }
        val next = s.direction.opposite
        if (next in available) s.copy(direction = next) else s
    }

    /** Switched-on map layers, from settings. */
    val layers: StateFlow<Set<MapLayer>> = settings.settings.map { it.mapLayers }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MapLayer.entries.toSet())

    private val allOverview = MutableStateFlow<List<OverviewRoute>>(emptyList())

    /** The all-lines overlay, minus switched-off modes. */
    val overview: StateFlow<List<OverviewRoute>> = combine(allOverview, layers) { routes, on ->
        routes.filter { MapLayer.forMode(it.mode) in on }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLayer(layer: MapLayer, visible: Boolean) {
        viewModelScope.launch { settingsRepository.setMapLayer(layer, visible) }
    }

    private var viewport: Viewport? = null
    private var areaJob: Job? = null

    /** Loads the stops in view once the camera settles, when zoomed in far enough to read them. */
    fun onViewportChanged(view: Viewport) {
        viewport = view
        areaJob?.cancel()
        areaJob = viewModelScope.launch {
            val on = layers.value
            val found = if (view.zoom < AREA_STOPS_ZOOM || MapLayer.STOPS !in on) emptyList()
            else stops.inArea(view.minLatitude, view.maxLatitude, view.minLongitude, view.maxLongitude, AREA_STOPS_LIMIT)
                .filter { MapLayer.forMode(it.mode) in on }
            _state.update { it.copy(areaStops = found) }
        }
    }

    fun onStopClick(stop: Stop) {
        _state.update { it.copy(preview = StopPreview(stop, emptyList()), pin = null) }
        viewModelScope.launch {
            val lines = stops.linesAtStop(stop.code)
            val transfers = stops.transfers(stop).map { it.stop }
            _state.update { if (it.preview?.stop?.code == stop.code) it.copy(preview = StopPreview(stop, lines, transfers)) else it }
        }
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun onMapLongClick(point: LatLng) = _state.update { it.copy(pin = point, preview = null) }

    fun clearPin() = _state.update { it.copy(pin = null) }

    /** A tap on empty map dismisses whatever card is open. */
    fun onMapClick() = _state.update { it.copy(preview = null, pin = null) }

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
                    it.copy(
                        line = line,
                        routes = routes,
                        announcements = announcements,
                        connections = connections,
                        loadingRoute = false,
                        // The direction serving the stop the user came from (a bus stop is on one
                        // side of the street); otherwise outbound. Loop lines have one direction.
                        direction = routes.firstOrNull { r -> fromStop != null && r.stops.dropLast(1).any { s -> s.code == fromStop } }?.direction
                            ?: routes.firstOrNull { r -> r.direction == Direction.OUTBOUND }?.direction
                            ?: routes.firstOrNull()?.direction ?: Direction.OUTBOUND,
                    )
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
        if (pinnedLine == null) {
            // "Show on map" from a stop or a favourite: centre on it and open its card. Any
            // chosen line is closed so the stop isn't hidden under it.
            viewModelScope.launch {
                selection.focusStop.collect { code ->
                    if (code == null) return@collect
                    selection.consumeFocus()
                    val stop = stops.stop(code) ?: return@collect
                    selection.select(null)
                    onStopClick(stop)
                    _state.update {
                        it.copy(cameraRequest = CameraRequest.to(stop.location, zoom = 16.5f))
                    }
                }
            }
        }
        viewModelScope.launch {
            allOverview.value = lines.overviewShapes()
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
                is LocationResult.Found -> _state.update {
                    // Zoomed in far enough that the stops around the user load by themselves.
                    it.copy(
                        userLocation = result.location,
                        cameraRequest = CameraRequest.to(result.location, zoom = 16f),
                        locating = false,
                        locationEnabled = true,
                    )
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
        const val KEY_FROM_STOP = "stop"

        /** Stops in view are drawn from this zoom on; below it they would crowd. */
        const val AREA_STOPS_ZOOM = 15f
        /** The area now includes the whole screen and a margin, so a little more room. */
        private const val AREA_STOPS_LIMIT = 260
        private const val NEXT_DEPARTURES = 3
    }
}
