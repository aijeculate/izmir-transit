package com.aijec.izmirtransit.core.model.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Google's encoded polyline format at 1e-5 precision (~1 m). Used to store route shapes compactly. */
object PolylineCodec {
    fun encode(points: List<LatLng>): String = buildString {
        var lastLat = 0L
        var lastLon = 0L
        for (point in points) {
            val lat = (point.latitude * 1e5).roundToLong()
            val lon = (point.longitude * 1e5).roundToLong()
            appendValue(lat - lastLat)
            appendValue(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
    }

    fun decode(encoded: String): List<LatLng> {
        val points = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, afterLat) = readValue(encoded, index)
            val (dLon, afterLon) = readValue(encoded, afterLat)
            index = afterLon
            lat += dLat
            lon += dLon
            points += LatLng(lat / 1e5, lon / 1e5)
        }
        return points
    }

    private fun StringBuilder.appendValue(value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        append((v + 63).toInt().toChar())
    }

    private fun readValue(encoded: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = start
        while (true) {
            require(index < encoded.length) { "Truncated polyline" }
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to index
    }
}

/** Removes consecutive duplicates, then Douglas–Peucker simplifies to [toleranceMeters]. */
fun simplifyPolyline(points: List<LatLng>, toleranceMeters: Double): List<LatLng> {
    val deduped = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }
    if (deduped.size <= 2) return deduped
    val keep = BooleanArray(deduped.size)
    keep[0] = true
    keep[deduped.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to deduped.lastIndex)
    while (stack.isNotEmpty()) {
        val (first, last) = stack.removeLast()
        var maxDistance = 0.0
        var maxIndex = -1
        for (i in first + 1 until last) {
            val d = perpendicularDistance(deduped[i], deduped[first], deduped[last])
            if (d > maxDistance) {
                maxDistance = d
                maxIndex = i
            }
        }
        if (maxIndex >= 0 && maxDistance > toleranceMeters) {
            keep[maxIndex] = true
            stack.addLast(first to maxIndex)
            stack.addLast(maxIndex to last)
        }
    }
    return deduped.filterIndexed { i, _ -> keep[i] }
}

private fun perpendicularDistance(p: LatLng, a: LatLng, b: LatLng): Double {
    val kx = METERS_PER_DEGREE * cos(p.latitude.toRadians())
    val ky = METERS_PER_DEGREE
    val abx = (b.longitude - a.longitude) * kx
    val aby = (b.latitude - a.latitude) * ky
    val apx = (p.longitude - a.longitude) * kx
    val apy = (p.latitude - a.latitude) * ky
    val length = sqrt(abx * abx + aby * aby)
    if (length == 0.0) return sqrt(apx * apx + apy * apy)
    return abs(abx * apy - aby * apx) / length
}
