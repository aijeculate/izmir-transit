package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.data.source.eshot.live.RawBusPosition
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.geo.BoundingBox
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.distanceMeters

/**
 * Removes the ghost records `hatotobuskonumlari` returns: the same OtobusId repeated 2–5
 * times, where one copy moves along the route and the others are frozen points 0.5–6 km
 * away or at (0,0). Seen on 20 Oct 2026; see research/FINDINGS.md.
 */
class GhostFilter(
    /**
     * Night: real buses within ~260 m (terminal loops), ghosts 500 m+. Daytime check on
     * 20 Oct 2026 (8 lines, ~70 buses): moving copies p99 120 m, nearest ghost cluster 479 m.
     */
    private val maxDistanceFromRouteMeters: Double = 300.0,
    private val movedThresholdMeters: Double = 10.0,
) {
    data class Result(val buses: List<LiveBus>, val dropped: Int)

    /**
     * Single-record check, used for the stop endpoints, which return ghosts too: on
     * 20 Oct 2026 stop 10007 listed two line 29 buses "2 stops away" that were 10.7 km off
     * the route while line 29's own position endpoint reported no buses.
     */
    fun isPlausible(position: LatLng, routes: Map<Direction, MeasuredPolyline>): Boolean {
        if (position !in BoundingBox.IZMIR) return false
        if (routes.isEmpty()) return true
        return routes.values.any { route ->
            (route.project(position)?.distanceFromLineMeters ?: Double.MAX_VALUE) <= maxDistanceFromRouteMeters
        }
    }

    /**
     * @param routes shapes of the line by direction; when empty the route check is skipped.
     * @param previousRaw all records from the previous poll, to detect which copy moved.
     * @param previousSelected the position chosen for each bus last time.
     */
    fun filter(
        lineCode: String,
        raw: List<RawBusPosition>,
        routes: Map<Direction, MeasuredPolyline>,
        previousRaw: List<RawBusPosition> = emptyList(),
        previousSelected: Map<Int, LatLng> = emptyMap(),
    ): Result {
        data class Candidate(val record: RawBusPosition, val distanceFromRoute: Double)

        val candidates = raw.mapNotNull { record ->
            if (record.position !in BoundingBox.IZMIR) return@mapNotNull null
            val distance = routes.values.minOfOrNull { route ->
                route.project(record.position)?.distanceFromLineMeters ?: Double.MAX_VALUE
            } ?: 0.0
            if (distance > maxDistanceFromRouteMeters) null else Candidate(record, distance)
        }

        val previousById = previousRaw.groupBy({ it.busId }, { it.position })
        val buses = candidates.groupBy { it.record.busId }.map { (busId, copies) ->
            val chosen = if (copies.size == 1) {
                copies.single()
            } else {
                val earlier = previousById[busId].orEmpty()
                val moved = copies.filter { copy ->
                    earlier.isNotEmpty() && earlier.none { distanceMeters(it, copy.record.position) < movedThresholdMeters }
                }
                val last = previousSelected[busId]
                when {
                    moved.isNotEmpty() -> moved.minBy { it.distanceFromRoute }
                    last != null -> copies.minBy { distanceMeters(it.record.position, last) }
                    else -> copies.minBy { it.distanceFromRoute }
                }
            }
            LiveBus(busId, lineCode, chosen.record.direction, chosen.record.position)
        }
        return Result(buses.sortedBy { it.busId }, dropped = raw.size - buses.size)
    }
}
