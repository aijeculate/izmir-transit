package com.aijec.izmirtransit.core.data.routing

import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** One line direction as the network builder receives it. */
class PatternInput(
    val line: Line,
    val direction: Direction,
    val stopCodes: List<String>,
    /** The drawn route, if any; bus stop times are estimated along it. */
    val shape: List<LatLng>,
    /** Buses: departures from the first stop (minutes). */
    val originDepartures: List<Int> = emptyList(),
    /** Rail and ferry: departures at each stop, with the vehicle's destination. */
    val stopDepartures: Map<String, List<Pair<Int, String>>> = emptyMap(),
)

/**
 * The day's network in arrays, ready for [JourneyPlanner]: stops, line directions
 * ("patterns") with minutes between their stops, and short walks between nearby stops.
 *
 * Bus timetables only give departures from the first stop, so a bus's time at later stops
 * is estimated from distance along its route at [BUS_METERS_PER_MINUTE], the speed the
 * arrival estimates were validated with. Rail and ferry have a timetable at every station.
 */
class TransitNetwork internal constructor(val stops: List<Stop>, internal val patterns: List<Pattern>) {
    internal val stopIndex: Map<String, Int> = stops.withIndex().associate { it.value.code to it.index }

    /** For each stop: (pattern, position in it) for every pattern calling there. */
    internal val patternsAt: Array<IntArray> = run {
        val lists = Array(stops.size) { ArrayList<Int>() }
        patterns.forEachIndexed { p, pattern ->
            pattern.stops.forEachIndexed { position, stop -> lists[stop] += p * POSITION_FACTOR + position }
        }
        Array(stops.size) { lists[it].toIntArray() }
    }

    private val grid = StopGrid(stops)

    /** For each stop: walkable neighbours as (stop, minutes). */
    internal val transfers: Array<List<Pair<Int, Int>>> = Array(stops.size) { s ->
        grid.near(stops[s].location, TRANSFER_RADIUS_METERS)
            .filter { it != s }
            .map { it to walkMinutes(distanceMeters(stops[s].location, stops[it].location)) }
    }

    /** Stops within [radiusMeters] of a point, with the metres to each. */
    internal fun stopsNear(point: LatLng, radiusMeters: Double): List<Pair<Int, Double>> =
        grid.near(point, radiusMeters).map { it to distanceMeters(point, stops[it].location) }

    internal class Pattern(
        val line: Line,
        val direction: Direction,
        val stops: IntArray,
        /** Minutes from the first stop to each stop. */
        val offsets: IntArray,
        /** Buses: departures from the first stop, sorted. */
        val origin: IntArray?,
        /** Rail and ferry: departures at each position, sorted; the last stop has none. */
        val perStop: Array<IntArray>?,
        val headsigns: Array<Array<String>>?,
        val path: MeasuredPolyline?,
        /** Each stop's distance along [path]. */
        val along: DoubleArray?,
    ) {
        val exact: Boolean get() = perStop != null

        /** The first departure from [position] at or after [minute]: (minute, last position reached). */
        fun nextDeparture(position: Int, minute: Int): Departure? {
            if (position >= stops.size - 1) return null
            if (perStop != null) {
                val times = perStop[position]
                val i = lowerBound(times, minute)
                return if (i < times.size) Departure(times[i], stops.size - 1, headsigns!![position][i]) else null
            }
            val times = origin ?: return null
            // A bus leaving the first stop at d is at this stop at d + offset.
            val i = lowerBound(times, minute - offsets[position])
            return if (i < times.size) Departure(times[i] + offsets[position], stops.size - 1, "") else null
        }
    }

    internal data class Departure(val minute: Int, val reach: Int, val headsign: String)

    companion object {
        /** City bus speed including stops, validated against real movements (see EtaEstimator). */
        const val BUS_METERS_PER_MINUTE = 300.0
        const val WALK_METERS_PER_MINUTE = 75.0
        /** Streets aren't straight: walking distance over straight-line distance. */
        const val WALK_DETOUR = 1.25
        const val TRANSFER_RADIUS_METERS = 400.0
        internal const val POSITION_FACTOR = 1 shl 10

        fun walkMinutes(straightMeters: Double): Int = ceil(straightMeters * WALK_DETOUR / WALK_METERS_PER_MINUTE).toInt()

        /** Average speed between stations where the timetable doesn't say (the last station). */
        private fun scheduledSpeed(mode: TransitMode): Double = when (mode) {
            TransitMode.METRO -> 550.0
            TransitMode.IZBAN -> 700.0
            TransitMode.TRAM -> 300.0
            TransitMode.FERRY -> 300.0
            TransitMode.BUS -> BUS_METERS_PER_MINUTE
        }

        fun build(stops: List<Stop>, inputs: List<PatternInput>): TransitNetwork {
            val index = stops.withIndex().associate { it.value.code to it.index }
            val patterns = inputs.flatMap { input ->
                val positions = input.stopCodes.mapNotNull(index::get)
                if (positions.size < 2) return@flatMap emptyList()
                val codes = positions.map { stops[it].code }
                val path = input.shape.takeIf { it.size >= 2 }?.let(::MeasuredPolyline)
                val along = path?.let { line ->
                    var from = 0.0
                    DoubleArray(positions.size) { i ->
                        val projected = line.projectAfter(stops[positions[i]].location, from)?.distanceAlongMeters ?: from
                        from = maxOf(from, projected)
                        from
                    }
                }
                val scheduled = input.stopDepartures.isNotEmpty()
                if (scheduled) {
                    val perStop = Array(codes.size) { i ->
                        if (i == codes.size - 1) emptyList() else input.stopDepartures[codes[i]].orEmpty().sortedBy { it.first }
                    }
                    val names = positions.map { TurkishText.fold(stops[it].name) }
                    // For timing a hop, only trains that leave the next station again count:
                    // a train ending there has no departure to measure against.
                    val continuing = List(codes.size) { i ->
                        perStop[i].filter { reachOf(it.second, names, i) > i + 1 }.map { it.first }
                    }
                    val offsets = scheduledOffsets(input.line.mode, positions.map { stops[it].location }, continuing, perStop.map { list -> list.map { it.first } })
                    // Trains that end early (an İZBAN to Menemen on the Aliağa line) become their
                    // own shorter pattern, so every pattern's trains run its whole length.
                    val byTerminus = HashMap<Int, Array<MutableList<Pair<Int, String>>>>()
                    for (i in 0 until codes.size - 1) for (departure in perStop[i]) {
                        val terminus = reachOf(departure.second, names, i)
                        byTerminus.getOrPut(terminus) { Array(codes.size) { ArrayList() } }[i] += departure
                    }
                    byTerminus.map { (terminus, departures) ->
                        val length = terminus + 1
                        TransitNetwork.Pattern(
                            line = input.line,
                            direction = input.direction,
                            stops = positions.take(length).toIntArray(),
                            offsets = offsets.copyOf(length),
                            origin = null,
                            perStop = Array(length) { i -> departures[i].map { it.first }.toIntArray() },
                            headsigns = Array(length) { i -> departures[i].map { it.second }.toTypedArray() },
                            path = path,
                            along = along?.copyOf(length),
                        )
                    }
                } else {
                    if (input.originDepartures.isEmpty()) return@flatMap emptyList()
                    val distances = along ?: run {
                        // No route drawn: straight lines between stops, a little longer.
                        var total = 0.0
                        DoubleArray(positions.size) { i ->
                            if (i > 0) total += distanceMeters(stops[positions[i - 1]].location, stops[positions[i]].location) * 1.3
                            total
                        }
                    }
                    listOf(
                        TransitNetwork.Pattern(
                            line = input.line,
                            direction = input.direction,
                            stops = positions.toIntArray(),
                            offsets = IntArray(positions.size) { (distances[it] / BUS_METERS_PER_MINUTE).roundToInt() },
                            origin = input.originDepartures.distinct().sorted().toIntArray(),
                            perStop = null,
                            headsigns = null,
                            path = path,
                            along = along,
                        ),
                    )
                }
            }
            return TransitNetwork(stops, patterns)
        }

        /**
         * Minutes from the first station, from the timetable: between two stations the time
         * is the median gap from a departure at one to the next departure at the other.
         * Headways are far longer than a hop, so the next departure is the same train.
         */
        private fun scheduledOffsets(mode: TransitMode, locations: List<LatLng>, continuing: List<List<Int>>, departures: List<List<Int>>): IntArray {
            val offsets = IntArray(locations.size)
            for (i in 1 until locations.size) {
                val from = continuing[i - 1]
                val to = departures[i]
                val toSorted = to.toIntArray()
                val gaps = if (from.isEmpty() || to.isEmpty()) emptyList() else from.mapNotNull { d ->
                    val j = lowerBound(toSorted, d)
                    if (j < to.size && to[j] - d <= 30) to[j] - d else null
                }
                val hop = if (gaps.size >= 3) {
                    gaps.sorted()[gaps.size / 2].coerceAtLeast(1)
                } else {
                    // The terminus has no departures: estimate from distance.
                    ceil(distanceMeters(locations[i - 1], locations[i]) / scheduledSpeed(mode)).toInt().coerceAtLeast(1) +
                        (if (mode == TransitMode.FERRY) 3 else 0)
                }
                offsets[i] = offsets[i - 1] + hop
            }
            return offsets
        }

        /** The position of the station a headsign names, after [from]; the last stop if none. */
        private fun reachOf(headsign: String, foldedNames: List<String>, from: Int): Int {
            val target = TurkishText.fold(headsign.substringAfterLast(",").trim())
            if (target.isEmpty()) return foldedNames.size - 1
            for (i in from + 1 until foldedNames.size) {
                val name = foldedNames[i]
                if (name == target || name.startsWith(target) || target.startsWith(name)) return i
            }
            return foldedNames.size - 1
        }
    }
}

internal fun lowerBound(sorted: IntArray, value: Int): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (sorted[mid] < value) low = mid + 1 else high = mid
    }
    return low
}

/** Buckets stops into ~500 m cells for quick "what's near here" lookups. */
private class StopGrid(private val stops: List<Stop>) {
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        stops.forEachIndexed { i, stop -> cells.getOrPut(key(cellX(stop.location), cellY(stop.location))) { ArrayList() } += i }
    }

    fun near(point: LatLng, radiusMeters: Double): List<Int> {
        val reach = ceil(radiusMeters / CELL_METERS).toInt()
        val cx = cellX(point)
        val cy = cellY(point)
        val found = ArrayList<Int>()
        for (dx in -reach..reach) for (dy in -reach..reach) {
            cells[key(cx + dx, cy + dy)]?.forEach { if (distanceMeters(point, stops[it].location) <= radiusMeters) found += it }
        }
        return found
    }

    private fun cellX(p: LatLng) = floor(p.longitude * METERS_PER_DEGREE * cos(Math.toRadians(REFERENCE_LATITUDE)) / CELL_METERS).toInt()
    private fun cellY(p: LatLng) = floor(p.latitude * METERS_PER_DEGREE / CELL_METERS).toInt()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val CELL_METERS = 500.0
        const val METERS_PER_DEGREE = 111_320.0
        const val REFERENCE_LATITUDE = 38.4
    }
}
