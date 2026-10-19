package com.aijec.izmirtransit.core.data.repository

import com.aijec.izmirtransit.core.data.live.EtaEstimator
import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.live.GhostFilter
import com.aijec.izmirtransit.core.data.live.map
import com.aijec.izmirtransit.core.data.live.pollLive
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekLiveDataSource
import com.aijec.izmirtransit.core.data.source.eshot.live.RawBusPosition
import com.aijec.izmirtransit.core.model.ApproachingBus
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Live positions and arrivals as polling flows. Collect them only for what is on screen
 * and only while started; polling stops as soon as collection does.
 */
class LiveBusRepository @Inject constructor(
    private val source: IztekLiveDataSource,
    private val lines: LineRepository,
    private val stops: StopRepository,
    private val ghostFilter: GhostFilter,
    private val etaEstimator: EtaEstimator,
    private val clock: Clock,
) {
    fun observeLine(lineCode: String, interval: Duration = DEFAULT_INTERVAL): Flow<LiveSnapshot<List<LiveBus>>> = flow {
        val routes = lines.measuredRoutes(lineCode)
        var previousRaw = emptyList<RawBusPosition>()
        var previousSelected = emptyMap<Int, LatLng>()
        emitAll(
            pollLive(interval, clock) {
                source.lineBusPositions(lineCode).map { raw ->
                    val result = ghostFilter.filter(lineCode, raw, routes, previousRaw, previousSelected)
                    previousRaw = raw
                    previousSelected = result.buses.associate { it.busId to it.position }
                    result.buses
                }
            },
        )
    }

    /** Buses approaching a stop, sorted by stops remaining, each with an approximate ETA. */
    fun observeStop(stopCode: String, interval: Duration = DEFAULT_INTERVAL): Flow<LiveSnapshot<List<ApproachingBus>>> = flow {
        val stopLocation = stops.stop(stopCode)?.location
        emitAll(
            pollLive(interval, clock) {
                when (val result = source.approachingBuses(stopCode)) {
                    is FetchResult.Failure -> result
                    is FetchResult.Success -> {
                        val buses = result.value
                            .distinctBy { it.busId to it.lineCode }
                            .filter { ghostFilter.isPlausible(it.position, lines.measuredRoutes(it.lineCode)) }
                            .map { bus ->
                                val route = lines.measuredRoutes(bus.lineCode)[bus.direction]
                                ApproachingBus(
                                    busId = bus.busId,
                                    lineCode = bus.lineCode,
                                    lineName = bus.lineName,
                                    direction = bus.direction,
                                    stopsRemaining = bus.stopsRemaining,
                                    position = bus.position,
                                    wheelchairAccessible = bus.wheelchairAccessible,
                                    bikeRack = bus.bikeRack,
                                    eta = etaEstimator.estimate(bus.position, bus.stopsRemaining, stopLocation, route),
                                )
                            }
                            .sortedWith(compareBy({ it.stopsRemaining }, { it.eta.minutes }))
                        FetchResult.Success(buses, result.skippedRecords)
                    }
                }
            },
        )
    }

    companion object {
        /** Brief: 10–15 s. Each screen's poll costs one request from the shared 1/s budget. */
        val DEFAULT_INTERVAL = 15.seconds
    }
}
