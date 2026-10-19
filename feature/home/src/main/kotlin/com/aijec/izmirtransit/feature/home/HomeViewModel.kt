package com.aijec.izmirtransit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.FavouritesRepository
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.location.LocationProvider
import com.aijec.izmirtransit.core.location.LocationResult
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.Stop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavouriteStop(val stop: Stop, val lineCodes: List<String>)

data class NearbyStopItem(val stop: Stop, val distanceMeters: Int, val lineCodes: List<String>)

sealed interface NearbyState {
    data object Idle : NearbyState
    data object Locating : NearbyState
    data object PermissionNeeded : NearbyState
    data object LocationOff : NearbyState
    data object Unavailable : NearbyState
    data class Found(val stops: List<NearbyStopItem>) : NearbyState
}

data class FavouritesState(
    val loaded: Boolean = false,
    val stops: List<FavouriteStop> = emptyList(),
    val lines: List<Line> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    favouritesRepository: FavouritesRepository,
    private val stops: StopRepository,
    private val lines: LineRepository,
    private val live: LiveBusRepository,
    private val location: LocationProvider,
) : ViewModel() {

    val favourites: StateFlow<FavouritesState> = favouritesRepository.favourites
        .mapLatest { favourites ->
            FavouritesState(
                loaded = true,
                stops = favourites.stopCodes.mapNotNull { code ->
                    stops.stop(code)?.let { FavouriteStop(it, stops.linesAtStop(code).map(Line::code)) }
                },
                lines = favourites.lineCodes.mapNotNull { lines.line(it) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavouritesState())

    /**
     * Live arrivals for the first few favourite stops, keyed by stop code. Each one polls
     * through the shared 1 req/s pacer, so the number is capped to keep updates timely.
     */
    val arrivals: StateFlow<Map<String, LiveSnapshot<List<ApproachingBus>>>> = favourites
        .map { state -> state.stops.take(MAX_LIVE_STOPS).map { it.stop.code } }
        .flatMapLatest { codes -> arrivalsFor(codes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun arrivalsFor(codes: List<String>): Flow<Map<String, LiveSnapshot<List<ApproachingBus>>>> =
        if (codes.isEmpty()) flowOf(emptyMap())
        else combine(codes.distinct().map { code -> live.observeStop(code).map { code to it } }) { it.toMap() }

    private val _nearby = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val nearby: StateFlow<NearbyState> = _nearby.asStateFlow()
    private var nearbyJob: Job? = null

    /** Called when the screen starts, after a permission grant, and on refresh. */
    fun refreshNearby() {
        if (!location.hasPermission()) {
            _nearby.value = NearbyState.PermissionNeeded
            return
        }
        if (nearbyJob?.isActive == true) return
        nearbyJob = viewModelScope.launch {
            val previous = _nearby.value
            if (previous !is NearbyState.Found) _nearby.value = NearbyState.Locating
            _nearby.value = when (val result = location.current()) {
                is LocationResult.Found -> NearbyState.Found(
                    stops.nearby(result.location, radiusMeters = NEARBY_RADIUS_METERS, limit = NEARBY_LIMIT).map {
                        NearbyStopItem(it.stop, it.distanceMeters.toInt(), stops.linesAtStop(it.stop.code).map(Line::code))
                    },
                )
                LocationResult.PermissionMissing -> NearbyState.PermissionNeeded
                LocationResult.LocationOff -> NearbyState.LocationOff
                // Keep showing the last list rather than replacing it with an error.
                LocationResult.Unavailable -> previous as? NearbyState.Found ?: NearbyState.Unavailable
            }
        }
    }

    private companion object {
        const val MAX_LIVE_STOPS = 5
        const val NEARBY_RADIUS_METERS = 750.0
        const val NEARBY_LIMIT = 12
    }
}

/** Convenience for the UI: the loading placeholder until the first poll lands. */
internal val LoadingArrivals = LiveSnapshot<List<ApproachingBus>>(null, LiveStatus.LOADING)
