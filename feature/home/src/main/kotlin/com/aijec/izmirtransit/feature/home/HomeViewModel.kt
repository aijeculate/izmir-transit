package com.aijec.izmirtransit.feature.home

import com.aijec.izmirtransit.core.data.repository.MapSelection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.FavouriteItem
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.model.ScheduledDeparture
import com.aijec.izmirtransit.core.model.isBusCode
import java.time.Clock
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavouriteStop(val stop: Stop, val lines: List<Line>)

data class NearbyStopItem(val stop: Stop, val distanceMeters: Int, val lines: List<Line>)

/** A station or pier's next timetabled departures, from [nowMinute] on the service day's clock. */
data class StationBoard(val nowMinute: Int, val departures: List<ScheduledDeparture>)

sealed interface NearbyState {
    data object Idle : NearbyState
    data object Locating : NearbyState
    data object PermissionNeeded : NearbyState
    data object LocationOff : NearbyState
    data object Unavailable : NearbyState
    data class Found(val stops: List<NearbyStopItem>) : NearbyState
}

/** One favourite as Home shows it, in the user's order. */
sealed interface FavouriteEntry {
    val item: FavouriteItem

    data class StopEntry(val favourite: FavouriteStop) : FavouriteEntry {
        override val item get() = FavouriteItem.StopItem(favourite.stop.code)
    }

    data class LineEntry(val line: Line) : FavouriteEntry {
        override val item get() = FavouriteItem.LineItem(line.code)
    }
}

data class FavouritesState(
    val loaded: Boolean = false,
    val entries: List<FavouriteEntry> = emptyList(),
) {
    val stops: List<FavouriteStop> get() = entries.filterIsInstance<FavouriteEntry.StopEntry>().map { it.favourite }
    val lines: List<Line> get() = entries.filterIsInstance<FavouriteEntry.LineEntry>().map { it.line }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val favouritesRepository: FavouritesRepository,
    private val stops: StopRepository,
    private val lines: LineRepository,
    private val live: LiveBusRepository,
    private val location: LocationProvider,
    private val clock: Clock,
    private val mapSelection: MapSelection,
) : ViewModel() {

    /** Asks the Map tab to centre on a stop; the caller then switches to it. */
    fun showOnMap(stopCode: String) = mapSelection.showStop(stopCode)

    fun moveFavourite(item: FavouriteItem, by: Int) {
        viewModelScope.launch { favouritesRepository.move(item, by) }
    }

    fun removeFavourite(item: FavouriteItem) {
        viewModelScope.launch {
            when (item) {
                is FavouriteItem.StopItem -> favouritesRepository.setStop(item.code, false)
                is FavouriteItem.LineItem -> favouritesRepository.setLine(item.code, false)
            }
        }
    }

    val favourites: StateFlow<FavouritesState> = favouritesRepository.favourites
        .mapLatest { favourites ->
            FavouritesState(
                loaded = true,
                entries = favourites.items.mapNotNull { item ->
                    when (item) {
                        is FavouriteItem.StopItem -> stops.stop(item.code)?.let {
                            FavouriteEntry.StopEntry(FavouriteStop(it, stops.linesAtStop(item.code)))
                        }
                        is FavouriteItem.LineItem -> lines.line(item.code)?.let { FavouriteEntry.LineEntry(it) }
                    }
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavouritesState())

    /**
     * Live arrivals for the first few favourite stops, keyed by stop code. Each one polls
     * through the shared 1 req/s pacer, so the number is capped to keep updates timely.
     */
    val arrivals: StateFlow<Map<String, LiveSnapshot<List<ApproachingBus>>>> = favourites
        .map { state -> state.stops.map { it.stop.code }.filter(::isBusCode).take(MAX_LIVE_STOPS) }
        .distinctUntilChanged()
        .flatMapLatest { codes -> arrivalsFor(codes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Favourite stations and piers run to timetable: their boards, re-read every minute. */
    val boards: StateFlow<Map<String, StationBoard>> = favourites
        .map { state -> state.stops.map { it.stop.code }.filterNot(::isBusCode) }
        .distinctUntilChanged()
        .flatMapLatest { codes ->
            if (codes.isEmpty()) flowOf(emptyMap())
            else flow {
                while (true) {
                    val moment = TimetableRepository.serviceMoment(TimetableRepository.localNow(clock))
                    emit(codes.associateWith { code ->
                        StationBoard(moment.minutesAfterMidnight, stops.scheduledDepartures(code, moment.serviceDay, moment.minutesAfterMidnight, limit = 3))
                    })
                    delay(60_000L - clock.millis() % 60_000L)
                }
            }
        }
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
                        NearbyStopItem(it.stop, it.distanceMeters.toInt(), stops.linesAtStop(it.stop.code))
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
