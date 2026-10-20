package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps buses moving between server updates. The İztek positions change less often than
 * we poll, so drawing them as-is makes buses jump. Each bus is tracked as a distance
 * along its route with a speed measured from successive real updates; between updates it
 * is extrapolated along the route, capped so a stale bus never drifts far. When a real
 * update arrives the drawn position glides to the corrected one instead of snapping.
 *
 * Not thread-safe; use from one thread (the UI).
 */
class BusMotion(
    /** Upper bound for a plausible city-bus speed (about 58 km/h). */
    private val maxSpeedMps: Double = 16.0,
    /** Never extrapolate further than this past the last real update. */
    private val maxExtrapolationMillis: Long = 45_000,
    /** Buses further than this from their route are drawn as reported, without prediction. */
    private val onRouteMeters: Double = 60.0,
    /** How long a correction glide takes. */
    private val correctionMillis: Long = 1_500,
    /**
     * Predicted speed is a little below measured, because buses stop at stops and the
     * glide back after an overshoot looks worse than lagging slightly.
     */
    private val speedFactor: Double = 0.85,
) {
    private sealed interface Track {
        val direction: Direction
    }

    /** Off-route or no shape: drawn where reported, gliding between reports. */
    private class Free(
        override val direction: Direction,
        var from: LatLng,
        var to: LatLng,
        var changedAt: Long,
    ) : Track

    private class OnRoute(
        override val direction: Direction,
        val route: MeasuredPolyline,
        var lastAlong: Double,
        var lastAt: Long,
        var speed: Double?,
        var correctionFrom: Double,
        var correctionAt: Long,
    ) : Track

    private val tracks = HashMap<Int, Track>()

    /** Feeds a fresh poll result. Buses missing from [buses] are dropped. */
    fun update(buses: List<LiveBus>, routes: Map<Direction, MeasuredPolyline>, nowMillis: Long) {
        val seen = HashSet<Int>()
        for (bus in buses) {
            seen += bus.busId
            val route = routes[bus.direction]
            val projection = route?.project(bus.position)
            val existing = tracks[bus.busId]
            if (route == null || projection == null || projection.distanceFromLineMeters > onRouteMeters) {
                val drawn = existing?.let { positionOf(it, nowMillis).first } ?: bus.position
                tracks[bus.busId] = Free(bus.direction, from = drawn, to = bus.position, changedAt = nowMillis)
                continue
            }
            val along = projection.distanceAlongMeters
            if (existing !is OnRoute || existing.direction != bus.direction || existing.route !== route) {
                tracks[bus.busId] = OnRoute(bus.direction, route, along, nowMillis, speed = null, correctionFrom = along, correctionAt = 0)
                continue
            }
            // Same coordinates as before: the server hasn't updated this bus. Keep
            // extrapolating from the last real update rather than restarting the clock.
            if (kotlin.math.abs(along - existing.lastAlong) < 1.0) continue

            val seconds = (nowMillis - existing.lastAt) / 1000.0
            val observed = if (seconds > 0) (along - existing.lastAlong) / seconds else 0.0
            existing.correctionFrom = drawnAlong(existing, nowMillis)
            existing.correctionAt = nowMillis
            existing.speed = when {
                // Backwards or teleport: stop predicting until it settles.
                observed < 0 || observed > maxSpeedMps -> 0.0
                existing.speed == null -> observed
                else -> 0.5 * existing.speed!! + 0.5 * observed
            }
            existing.lastAlong = along
            existing.lastAt = nowMillis
        }
        tracks.keys.retainAll(seen)
    }

    /** Where to draw every tracked bus at [nowMillis]. */
    fun positionsAt(nowMillis: Long): Map<Int, Pair<LatLng, Direction>> =
        tracks.mapValues { (_, track) -> positionOf(track, nowMillis) }

    private fun positionOf(track: Track, nowMillis: Long): Pair<LatLng, Direction> = when (track) {
        is Free -> {
            val t = easedFraction(nowMillis - track.changedAt)
            LatLng(
                track.from.latitude + (track.to.latitude - track.from.latitude) * t,
                track.from.longitude + (track.to.longitude - track.from.longitude) * t,
            ) to track.direction
        }
        is OnRoute -> track.route.pointAt(drawnAlong(track, nowMillis)) to track.direction
    }

    /** Predicted distance along the route, blended out of the last correction. */
    private fun drawnAlong(track: OnRoute, nowMillis: Long): Double {
        val elapsed = min(max(nowMillis - track.lastAt, 0), maxExtrapolationMillis)
        val predicted = track.lastAlong + (track.speed ?: 0.0) * speedFactor * elapsed / 1000.0
        val clamped = min(predicted, track.route.lengthMeters)
        val t = easedFraction(nowMillis - track.correctionAt)
        return track.correctionFrom + (clamped - track.correctionFrom) * t
    }

    private fun easedFraction(elapsedMillis: Long): Double {
        val x = (elapsedMillis.toDouble() / correctionMillis).coerceIn(0.0, 1.0)
        return 1 - (1 - x) * (1 - x) // ease-out
    }
}
