package com.aijec.izmirtransit.feature.planner

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.places.PlaceSuggestion
import com.aijec.izmirtransit.core.data.places.PlacesRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.data.routing.JourneyPlanner
import com.aijec.izmirtransit.core.data.routing.NetworkRepository
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.location.LocationResult
import com.aijec.izmirtransit.core.map.MapPlan
import com.aijec.izmirtransit.core.map.PlanLegShape
import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** One end of the trip. [isMyLocation] shows "My location" instead of a name. */
data class Endpoint(val name: String, val location: LatLng, val isMyLocation: Boolean = false)

enum class Field { FROM, TO }

/** A search result: one of our stops, or a Google place (whose location is fetched on choice). */
sealed interface Suggestion {
    data class StopResult(val stop: Stop) : Suggestion
    data class Place(val place: PlaceSuggestion) : Suggestion
    data object MyLocation : Suggestion
}

enum class PlannerMessage { LOCATION_PERMISSION, LOCATION_OFF, LOCATION_UNAVAILABLE, NO_ROUTE, PLACE_FAILED }

data class PlannerUiState(
    val from: Endpoint? = null,
    val to: Endpoint? = null,
    /** The field being searched, or null when the search list is closed. */
    val editing: Field? = null,
    val query: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    /** Leave now, or this many minutes later. */
    val departInMinutes: Int = 0,
    val locating: Boolean = false,
    val planning: Boolean = false,
    val journeys: List<Journey> = emptyList(),
    val selected: Int = 0,
    /** Nothing runs any more today: these are the first trips of the next service day. */
    val nextDay: Boolean = false,
    val message: PlannerMessage? = null,
    val placesEnabled: Boolean = false,
) {
    val selectedJourney: Journey? get() = journeys.getOrNull(selected)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val stops: StopRepository,
    private val places: PlacesRepository,
    private val networks: NetworkRepository,
    private val location: LocationProvider,
    private val clock: Clock,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(PlannerUiState(placesEnabled = places.enabled))
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()
    private val query = MutableStateFlow("")
    private var planJob: Job? = null

    init {
        // A destination chosen on the map (a stop or a long-pressed point) arrives as arguments.
        val lat = savedState.get<String>(ARG_LAT)?.toDoubleOrNull()
        val lon = savedState.get<String>(ARG_LON)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            _state.update { it.copy(to = Endpoint(savedState.get<String>(ARG_NAME).orEmpty(), LatLng(lat, lon))) }
        } else {
            _state.update { it.copy(editing = Field.TO) }
        }
        if (location.hasPermission()) useMyLocation() else _state.update { it.copy(message = PlannerMessage.LOCATION_PERMISSION) }
        // Builds the day's network in the background while the user types.
        viewModelScope.launch { networks.network(serviceMoment().serviceDay) }

        viewModelScope.launch {
            query.debounce(250).collectLatest { text ->
                if (text.isBlank()) {
                    _state.update { it.copy(suggestions = baseSuggestions(it.editing)) }
                    return@collectLatest
                }
                val stopResults = stops.search(text, limit = 6).map { Suggestion.StopResult(it) }
                _state.update { it.copy(suggestions = stopResults) }
                val placeResults = runCatching { places.autocomplete(text) }.getOrDefault(emptyList()).map { Suggestion.Place(it) }
                // Places first: "where I want to go" is usually an address or a venue, not a stop.
                _state.update { if (it.query == text) it.copy(suggestions = placeResults.take(5) + stopResults) else it }
            }
        }
    }

    private fun baseSuggestions(field: Field?): List<Suggestion> = if (field == Field.FROM) listOf(Suggestion.MyLocation) else emptyList()

    fun edit(field: Field?) {
        _state.update { it.copy(editing = field, query = "", suggestions = baseSuggestions(field)) }
        query.value = ""
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
    }

    fun choose(suggestion: Suggestion) {
        val field = _state.value.editing ?: Field.TO
        when (suggestion) {
            Suggestion.MyLocation -> {
                edit(null)
                useMyLocation()
            }
            is Suggestion.StopResult -> set(field, Endpoint(suggestion.stop.name, suggestion.stop.location))
            is Suggestion.Place -> viewModelScope.launch {
                runCatching { places.location(suggestion.place.placeId) }
                    .onSuccess { set(field, Endpoint(suggestion.place.title, it)) }
                    .onFailure { _state.update { s -> s.copy(message = PlannerMessage.PLACE_FAILED) } }
            }
        }
    }

    private fun set(field: Field, endpoint: Endpoint) {
        _state.update { if (field == Field.FROM) it.copy(from = endpoint) else it.copy(to = endpoint) }
        edit(null)
        plan()
    }

    fun swap() {
        _state.update { it.copy(from = it.to, to = it.from) }
        plan()
    }

    fun setDepartIn(minutes: Int) {
        _state.update { it.copy(departInMinutes = minutes) }
        plan()
    }

    fun select(index: Int) = _state.update { it.copy(selected = index) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun useMyLocation() {
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            when (val result = location.current()) {
                is LocationResult.Found -> {
                    _state.update { it.copy(from = Endpoint("", result.location, isMyLocation = true), locating = false) }
                    plan()
                }
                LocationResult.PermissionMissing -> _state.update { it.copy(locating = false, message = PlannerMessage.LOCATION_PERMISSION) }
                LocationResult.LocationOff -> _state.update { it.copy(locating = false, message = PlannerMessage.LOCATION_OFF) }
                LocationResult.Unavailable -> _state.update { it.copy(locating = false, message = PlannerMessage.LOCATION_UNAVAILABLE) }
            }
        }
    }

    private fun serviceMoment() =
        TimetableRepository.serviceMoment(TimetableRepository.localNow(clock).plusMinutes(_state.value.departInMinutes.toLong()))

    private fun plan() {
        val from = _state.value.from ?: return
        val to = _state.value.to ?: return
        planJob?.cancel()
        _state.update { it.copy(planning = true, journeys = emptyList(), selected = 0) }
        planJob = viewModelScope.launch {
            val now = TimetableRepository.localNow(clock).plusMinutes(_state.value.departInMinutes.toLong())
            val moment = TimetableRepository.serviceMoment(now)
            var journeys = planOn(moment.serviceDay, moment.minutesAfterMidnight, from, to)
            // Late at night: offer the first trips of the next service day (from 04:00).
            val nextDay = journeys.none { it.rides.isNotEmpty() }
            if (nextDay) {
                val date = if (now.hour < TimetableRepository.SERVICE_DAY_START_HOUR) now.toLocalDate() else now.toLocalDate().plusDays(1)
                val morning = planOn(TimetableRepository.serviceDayFor(date), TimetableRepository.SERVICE_DAY_START_HOUR * 60, from, to)
                    .filter { it.rides.isNotEmpty() }
                journeys = journeys + morning
            }
            _state.update {
                it.copy(
                    planning = false,
                    journeys = journeys,
                    selected = 0,
                    nextDay = nextDay && journeys.any { j -> j.rides.isNotEmpty() },
                    message = if (journeys.isEmpty()) PlannerMessage.NO_ROUTE else it.message,
                )
            }
        }
    }

    private suspend fun planOn(day: com.aijec.izmirtransit.core.model.ServiceDay, minute: Int, from: Endpoint, to: Endpoint): List<Journey> {
        val network = networks.network(day)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            JourneyPlanner(network).plan(from.location, to.location, minute)
        }
    }

    companion object {
        const val ARG_LAT = "lat"
        const val ARG_LON = "lon"
        const val ARG_NAME = "name"

        /** The journey as map shapes: walks dotted, rides in their line's colour. */
        fun mapPlan(journey: Journey, from: LatLng, to: LatLng): MapPlan = MapPlan(
            legs = journey.legs.map { leg ->
                when (leg) {
                    is JourneyLeg.Walk -> PlanLegShape(listOf(leg.from, leg.to), mode = null, color = null)
                    is JourneyLeg.Ride -> PlanLegShape(leg.path, leg.line.mode, leg.line.color, board = leg.board, alight = leg.alight)
                }
            },
            origin = from,
            destination = to,
        )
    }
}
