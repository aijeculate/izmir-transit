package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline

/**
 * Works out which way each bus is really going from where it is and how it moves, instead
 * of trusting the live feed's `Yon`. On some lines `Yon` disagrees with the dataset's
 * directions (line 233, 22 Oct 2026: the stop endpoint listed a bus 3 stops away, the line
 * map hid it), and the map's one-direction view then dropped the bus.
 *
 * - Near only one direction's route: that direction.
 * - Near both (a shared street): the one the bus moves forward along, since the two shapes
 *   run opposite ways; it is remembered while the bus stays ambiguous.
 * - Otherwise: the last direction worked out for the bus, else what the feed said.
 *
 * Keeps per-bus state between polls, so use one instance per polled line.
 */
class DirectionResolver(
    private val nearRouteMeters: Double = 60.0,
    private val minProgressMeters: Double = 20.0,
) {
    private val lastAlong = mutableMapOf<Int, Map<Direction, Double>>()
    private val resolved = mutableMapOf<Int, Direction>()

    fun resolve(buses: List<LiveBus>, routes: Map<Direction, MeasuredPolyline>): List<LiveBus> {
        if (routes.size < 2) return buses // loop lines and lines without shapes
        return buses.map { bus ->
            val projections = routes.mapValues { (_, route) -> route.project(bus.position) }
            val near = projections.filterValues { it != null && it.distanceFromLineMeters <= nearRouteMeters }.keys
            val along = projections.mapNotNull { (direction, projection) -> projection?.let { direction to it.distanceAlongMeters } }.toMap()
            val previous = lastAlong[bus.busId]
            lastAlong[bus.busId] = along

            val direction = when {
                near.size == 1 -> near.single()
                near.size > 1 && previous != null -> near.firstOrNull { d ->
                    val now = along[d] ?: return@firstOrNull false
                    val before = previous[d] ?: return@firstOrNull false
                    now - before >= minProgressMeters
                } ?: resolved[bus.busId]
                else -> resolved[bus.busId]
            } ?: bus.direction
            resolved[bus.busId] = direction
            if (direction == bus.direction) bus else bus.copy(direction = direction)
        }
    }
}
