package com.aijec.izmirtransit.core.model.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val latitude: Double, val longitude: Double)

data class BoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    operator fun contains(point: LatLng): Boolean =
        point.latitude in minLatitude..maxLatitude && point.longitude in minLongitude..maxLongitude

    companion object {
        /** İzmir province with a margin. Stops span lat 37.84–39.34, lon 26.30–28.22. */
        val IZMIR = BoundingBox(37.5, 39.6, 26.0, 28.6)

        fun around(center: LatLng, radiusMeters: Double): BoundingBox {
            val dLat = radiusMeters / METERS_PER_DEGREE
            val dLon = radiusMeters / (METERS_PER_DEGREE * cos(center.latitude.toRadians()))
            return BoundingBox(
                center.latitude - dLat, center.latitude + dLat,
                center.longitude - dLon, center.longitude + dLon,
            )
        }
    }
}

const val EARTH_RADIUS_METERS = 6_371_008.8
internal const val METERS_PER_DEGREE = EARTH_RADIUS_METERS * PI / 180.0

/** A stop this close to a route is on it (stops sit up to ~20 m off the drawn line). */
private const val NEAR_METERS = 50.0

internal fun Double.toRadians() = this * PI / 180.0

fun distanceMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.latitude - a.latitude).toRadians()
    val dLon = (b.longitude - a.longitude).toRadians()
    val h = sin(dLat / 2).let { it * it } +
        cos(a.latitude.toRadians()) * cos(b.latitude.toRadians()) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Where a point falls on a polyline. */
data class PolylineProjection(
    /** Metres from the start of the polyline to the snapped point. */
    val distanceAlongMeters: Double,
    /** Metres from the point to the polyline. */
    val distanceFromLineMeters: Double,
    val snapped: LatLng,
)

/**
 * A polyline with precomputed cumulative lengths, for snapping points and measuring
 * distance along a route. Uses a local flat projection per segment, which is accurate
 * to well under a metre at city scale.
 */
class MeasuredPolyline(val points: List<LatLng>) {
    private val cumulative = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(points[i - 1], points[i])
        }
    }

    val lengthMeters: Double get() = if (points.isEmpty()) 0.0 else cumulative.last()

    /** The point [distanceMeters] along the line, clamped to its ends. */
    fun pointAt(distanceMeters: Double): LatLng {
        require(points.isNotEmpty()) { "Empty polyline" }
        if (distanceMeters <= 0 || points.size == 1) return points.first()
        if (distanceMeters >= lengthMeters) return points.last()
        var index = cumulative.binarySearch(distanceMeters)
        if (index >= 0) return points[index]
        index = -index - 2 // segment start
        val a = points[index]
        val b = points[index + 1]
        val segment = cumulative[index + 1] - cumulative[index]
        val t = if (segment == 0.0) 0.0 else (distanceMeters - cumulative[index]) / segment
        return LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
    }

    /**
     * Like [project], but only onto the part of the line from [fromMeters] on: stops taken in
     * order then never snap back to an earlier pass of a looping route. The first stretch
     * that passes within [NEAR_METERS] wins over a closer one further on, so a stop near a
     * loop's start isn't snapped to its end (line 27 timed nine stops at zero minutes).
     */
    fun projectAfter(point: LatLng, fromMeters: Double): PolylineProjection? {
        if (points.size < 2) return project(point)
        val start = (cumulative.binarySearch(fromMeters).let { if (it >= 0) it else -it - 2 }).coerceIn(0, points.size - 2)
        val kx = METERS_PER_DEGREE * cos(point.latitude.toRadians())
        val ky = METERS_PER_DEGREE
        var best: PolylineProjection? = null
        var near: PolylineProjection? = null
        for (i in start until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val abx = (b.longitude - a.longitude) * kx
            val aby = (b.latitude - a.latitude) * ky
            val apx = (point.longitude - a.longitude) * kx
            val apy = (point.latitude - a.latitude) * ky
            val lengthSquared = abx * abx + aby * aby
            val segmentLength = cumulative[i + 1] - cumulative[i]
            val minT = if (i == start && segmentLength > 0) ((fromMeters - cumulative[i]) / segmentLength).coerceIn(0.0, 1.0) else 0.0
            val t = if (lengthSquared == 0.0) minT else ((apx * abx + apy * aby) / lengthSquared).coerceIn(minT, 1.0)
            val dx = apx - t * abx
            val dy = apy - t * aby
            val distance = sqrt(dx * dx + dy * dy)
            if (near != null && distance > NEAR_METERS) break
            if (best == null || distance < best.distanceFromLineMeters) {
                val snapped = LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
                best = PolylineProjection(cumulative[i] + t * segmentLength, distance, snapped)
            }
            if (distance <= NEAR_METERS && (near == null || distance < near.distanceFromLineMeters)) near = best
        }
        return near ?: best
    }

    /** The part of the line between two distances along it, for drawing a ride. */
    fun slice(fromMeters: Double, toMeters: Double): List<LatLng> {
        if (points.size < 2 || toMeters <= fromMeters) return listOf(pointAt(fromMeters), pointAt(toMeters))
        val inner = points.indices.filter { cumulative[it] > fromMeters && cumulative[it] < toMeters }.map { points[it] }
        return listOf(pointAt(fromMeters)) + inner + pointAt(toMeters)
    }

    fun project(point: LatLng): PolylineProjection? {
        if (points.isEmpty()) return null
        if (points.size == 1) {
            return PolylineProjection(0.0, distanceMeters(point, points[0]), points[0])
        }
        val kx = METERS_PER_DEGREE * cos(point.latitude.toRadians())
        val ky = METERS_PER_DEGREE
        var best: PolylineProjection? = null
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val abx = (b.longitude - a.longitude) * kx
            val aby = (b.latitude - a.latitude) * ky
            val apx = (point.longitude - a.longitude) * kx
            val apy = (point.latitude - a.latitude) * ky
            val lengthSquared = abx * abx + aby * aby
            val t = if (lengthSquared == 0.0) 0.0 else ((apx * abx + apy * aby) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = apx - t * abx
            val dy = apy - t * aby
            val distance = sqrt(dx * dx + dy * dy)
            if (best == null || distance < best.distanceFromLineMeters) {
                val snapped = LatLng(
                    a.latitude + t * (b.latitude - a.latitude),
                    a.longitude + t * (b.longitude - a.longitude),
                )
                val along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                best = PolylineProjection(along, distance, snapped)
            }
        }
        return best
    }
}
