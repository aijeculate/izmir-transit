package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.csv.forEachCsvRow
import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.database.DepartureEntity
import com.aijec.izmirtransit.core.database.LineEntity
import com.aijec.izmirtransit.core.database.LineStopEntity
import com.aijec.izmirtransit.core.database.MetaEntity
import com.aijec.izmirtransit.core.database.ShapeEntity
import com.aijec.izmirtransit.core.database.StaticDataSet
import com.aijec.izmirtransit.core.database.StopDepartureEntity
import com.aijec.izmirtransit.core.database.StopEntity
import com.aijec.izmirtransit.core.database.StopLineEntity
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.PolylineCodec
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.PriorityQueue
import java.util.zip.ZipFile

/** The three rail GTFS feeds, each published by its operator. None of them has shapes.txt. */
enum class RailFeed(val mode: TransitMode, val fileName: String, val url: String, val stopPrefix: String) {
    METRO(TransitMode.METRO, "rail-metro-gtfs.zip", "https://www.izmirmetro.com.tr/gtfs/rail-metro-gtfs.zip", "M"),
    TRAM(TransitMode.TRAM, "rail-tramizmir-gtfs.zip", "https://www.tramizmir.com/gtfs/rail-tramizmir-gtfs.zip", "T"),
    IZBAN(TransitMode.IZBAN, "rail-izban-gtfs.zip", "https://www.izban.com.tr/gtfs/rail-izban-gtfs.zip", "I"),
}

/**
 * Builds metro, tram or İZBAN tables from the operator's GTFS feed. Codes get a prefix
 * ("M12", "T-KONAK") so they never collide with numeric ESHOT codes.
 *
 * - Stop order per line direction merges every trip's stops, so short runs and express
 *   trips add no gaps; the route is drawn through the stations in that order.
 * - İZBAN's feed is a 2023 snapshot whose direction_id doesn't follow travel direction,
 *   so its direction comes from geography: southbound (towards Selçuk) is outbound.
 * - Every stop time becomes a [StopDepartureEntity], the station board; each trip's first
 *   departure goes in the line timetable.
 * - Where the metro and tram websites publish newer service bands ([RailFrequencies]),
 *   a line's trips on that day are laid out on the bands instead, with the GTFS's stations
 *   and running times.
 */
object RailImporter {
    private val GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE

    /** Badge names for the tram routes; the feed has no short names. */
    private val TRAM_NAMES = mapOf(
        "KONAK" to "Konak",
        "KARSIYAKA" to "Karşıyaka",
        "CIGLI_IC" to "Çiğli İç",
        "CIGLI_DIS" to "Çiğli Dış",
        "TURUNCU" to "Ring",
    )

    /** The İZBAN feed has no colour; this teal keeps it apart from the blue metro. */
    const val IZBAN_COLOR = 0xFF00838F.toInt()
    const val IZBAN_LINE = "IZBAN"

    private val MODE_WORDS = mapOf(
        TransitMode.METRO to "metro",
        TransitMode.TRAM to "tramvay tram",
        TransitMode.IZBAN to "izban banliyo tren",
    )

    private class Route(val lineCode: String, val displayCode: String, val name: String, val color: Int?)
    private class Trip(val lineCode: String, val days: Set<ServiceDay>, val directionId: Int, val headsign: String, val wheelchair: Boolean)
    private class StopTime(val sequence: Int, val stopId: String, val minute: Int)
    private class RailStop(val name: String, val location: LatLng)
    private class ResolvedTrip(val trip: Trip, val direction: Direction, val calls: List<StopTime>)

    fun build(
        feed: RailFeed,
        zipFile: File,
        now: Instant = Instant.now(),
        frequencies: List<RailFrequencies.Band>? = RailFrequencies.readBeside(feed, zipFile),
    ): ImportResult = ZipFile(zipFile).use { zip ->
        fun reader(name: String) = zip.getEntry(name)?.let { InputStreamReader(zip.getInputStream(it), Charsets.UTF_8) }
            ?: error("$name missing from ${zipFile.name}")
        val issues = ImportIssues()
        val warnings = mutableListOf<String>()
        val mode = feed.mode.name

        val serviceDays = HashMap<String, Set<ServiceDay>>()
        val ends = ArrayList<LocalDate>()
        reader("calendar.txt").forEachCsvRow(',') { row ->
            val days = buildSet {
                if (listOf("monday", "tuesday", "wednesday", "thursday", "friday").any { row[it] == "1" }) add(ServiceDay.WEEKDAY)
                if (row["saturday"] == "1") add(ServiceDay.SATURDAY)
                if (row["sunday"] == "1") add(ServiceDay.SUNDAY)
            }
            serviceDays[row["service_id"]] = days
            runCatching { LocalDate.parse(row["end_date"], GTFS_DATE) }.getOrNull()?.let(ends::add)
        }
        val serviceEnd = ends.maxOrNull()
        if (serviceEnd != null && serviceEnd.isBefore(now.atZone(TimetableZone).toLocalDate())) {
            warnings += "${feed.name} calendar ended $serviceEnd; its timetable is used anyway"
        }

        val routes = HashMap<String, Route>()
        reader("routes.txt").forEachCsvRow(',') { row ->
            val id = row["route_id"]
            val color = row["route_color"].takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            routes[id] = when (feed) {
                RailFeed.METRO -> {
                    val short = row["route_short_name"].ifEmpty { "M$id" }
                    Route(short, short, row["route_long_name"], color)
                }
                RailFeed.TRAM -> Route("T-$id", TRAM_NAMES[id] ?: row["route_long_name"], row["route_long_name"], color)
                RailFeed.IZBAN -> Route(IZBAN_LINE, "İZBAN", "İZBAN", IZBAN_COLOR)
            }
        }

        val stops = HashMap<String, RailStop>()
        reader("stops.txt").forEachCsvRow(',') { row ->
            val lat = row["stop_lat"].toDoubleOrNull()
            val lon = row["stop_lon"].toDoubleOrNull()
            if (lat == null || lon == null || row["location_type"] == "1") issues.add("stop skipped", row)
            else stops[row["stop_id"]] = RailStop(row["stop_name"], LatLng(lat, lon))
        }

        val trips = HashMap<String, Trip>()
        reader("trips.txt").forEachCsvRow(',') { row ->
            val route = routes[row["route_id"]]
            val days = serviceDays[row["service_id"]]
            if (route == null || days.isNullOrEmpty()) issues.add("trip with unknown route or service", row)
            else trips[row["trip_id"]] = Trip(
                lineCode = route.lineCode,
                days = days,
                directionId = row["direction_id"].toIntOrNull() ?: 0,
                headsign = row["trip_headsign"],
                wheelchair = row["wheelchair_accessible"] == "1",
            )
        }

        val stopTimes = HashMap<String, MutableList<StopTime>>()
        reader("stop_times.txt").forEachCsvRow(',') { row ->
            val trip = row["trip_id"]
            val minute = parseMinutes(row["departure_time"].ifEmpty { row["arrival_time"] })
            val sequence = row["stop_sequence"].toIntOrNull()
            when {
                trip !in trips -> issues.add("stop time for unknown trip", row)
                row["stop_id"] !in stops -> issues.add("stop time at unknown stop", row)
                minute == null || sequence == null -> issues.add("stop time without time or sequence", row)
                else -> stopTimes.getOrPut(trip) { ArrayList() } += StopTime(sequence, row["stop_id"], minute)
            }
        }

        // Resolve each trip's direction and ordered calls.
        val gtfsTrips = stopTimes.mapNotNull { (tripId, times) ->
            val calls = times.sortedBy { it.sequence }
            if (calls.size < 2) return@mapNotNull null.also { issues.add("trip with fewer than 2 stops", tripId) }
            val trip = trips.getValue(tripId)
            val direction = if (feed == RailFeed.IZBAN) {
                val southbound = stops.getValue(calls.last().stopId).location.latitude < stops.getValue(calls.first().stopId).location.latitude
                if (southbound) Direction.OUTBOUND else Direction.RETURN
            } else {
                if (trip.directionId == 0) Direction.OUTBOUND else Direction.RETURN
            }
            ResolvedTrip(trip, direction, calls)
        }
        val lineCodesInFeed = routes.values.map { it.lineCode }.distinct()
        val resolved = if (frequencies.isNullOrEmpty()) gtfsTrips else {
            // Lay each banded line's trips out on the bands, one template trip per pattern
            // (the GTFS's most common stop sequence for that pattern and direction).
            class Generated(val trip: ResolvedTrip, val everyMinutes: Double, val stops: Int)
            val replaced = HashSet<Pair<String, ServiceDay>>()
            val generated = ArrayList<Generated>()
            for ((siteName, bands) in frequencies.groupBy { it.lineName }) {
                val lineCode = RailFrequencies.lineCodeFor(feed, siteName, lineCodesInFeed)
                if (lineCode == null) {
                    warnings += "${feed.name}: no line for website schedule \"$siteName\""
                    continue
                }
                val ends = RailFrequencies.terminals(siteName)
                for (direction in Direction.entries) {
                    val candidates = gtfsTrips.filter { t ->
                        t.trip.lineCode == lineCode && t.direction == direction && (
                            ends == null || RailFrequencies.sameEnds(
                                ends,
                                stops.getValue(t.calls.first().stopId).name,
                                stops.getValue(t.calls.last().stopId).name,
                            )
                            )
                    }
                    if (candidates.isEmpty()) continue
                    val template = candidates.groupBy { t -> t.calls.map { it.stopId } }.maxBy { it.value.size }.value.first()
                    val first = template.calls.first().minute
                    for ((day, dayBands) in bands.groupBy { it.day }) {
                        replaced += lineCode to day
                        val every = dayBands.minOf { it.everyMinutes }
                        for (start in RailFrequencies.departures(dayBands)) {
                            val trip = ResolvedTrip(
                                Trip(lineCode, setOf(day), template.trip.directionId, template.trip.headsign, template.trip.wheelchair),
                                direction,
                                template.calls.map { StopTime(it.sequence, it.stopId, start + it.minute - first) },
                            )
                            generated += Generated(trip, every, template.calls.size)
                        }
                    }
                }
            }
            if (generated.isEmpty()) {
                gtfsTrips
            } else {
                // Two patterns can share track (the metro's every-4-minutes trunk includes the
                // every-8 trains that go on to Narlıdere): longer patterns go first, and a
                // shorter trip passing its first station within half its headway of one
                // already kept is the same train.
                val kept = ArrayList<ResolvedTrip>()
                val passing = HashMap<Triple<String, Direction, ServiceDay>, MutableMap<String, MutableList<Int>>>()
                for (g in generated.sortedByDescending { it.stops }) {
                    val day = g.trip.trip.days.single()
                    val times = passing.getOrPut(Triple(g.trip.trip.lineCode, g.trip.direction, day)) { HashMap() }
                    val firstCall = g.trip.calls.first()
                    val same = times[firstCall.stopId].orEmpty().any { kotlin.math.abs(it - firstCall.minute) < g.everyMinutes / 2 }
                    if (same) continue
                    kept += g.trip
                    g.trip.calls.forEach { times.getOrPut(it.stopId) { ArrayList() } += it.minute }
                }
                warnings += "${feed.name}: ${replaced.size} line-days timed from the website's service bands"
                // GTFS trips keep only the days the website doesn't cover.
                val rest = gtfsTrips.mapNotNull { t ->
                    val days = t.trip.days.filterTo(HashSet()) { (t.trip.lineCode to it) !in replaced }
                    when {
                        days.isEmpty() -> null
                        days.size == t.trip.days.size -> t
                        else -> ResolvedTrip(Trip(t.trip.lineCode, days, t.trip.directionId, t.trip.headsign, t.trip.wheelchair), t.direction, t.calls)
                    }
                }
                rest + kept
            }
        }
        val byLineDirection = resolved.groupBy { it.trip.lineCode to it.direction }

        val lineStops = ArrayList<LineStopEntity>()
        val shapes = ArrayList<ShapeEntity>()
        val order = HashMap<Pair<String, Direction>, List<String>>()
        for ((key, group) in byLineDirection) {
            val sequences = group.map { trip -> trip.calls.map { it.stopId } }
            val merged = mergeStopOrder(sequences)
            order[key] = merged
            merged.forEachIndexed { i, id ->
                lineStops += LineStopEntity(mode, key.first, key.second.datasetCode, i, feed.stopPrefix + id, LineStopEntity.SOURCE_GTFS)
            }
            // A ring line's usual trip starts and ends at the same station: close the loop.
            val typical = sequences.groupingBy { it }.eachCount().maxBy { it.value }.key
            val ids = if (typical.size > 2 && typical.first() == typical.last()) merged + merged.first() else merged
            val points = ids.map { stops.getValue(it).location }
            shapes += ShapeEntity(mode, key.first, key.second.datasetCode, PolylineCodec.encode(points), MeasuredPolyline(points).lengthMeters)
        }

        val lineCodes = lineCodesInFeed.filter { code -> byLineDirection.keys.any { it.first == code } }
        val lines = lineCodes.map { code ->
            val route = routes.values.first { it.lineCode == code }
            val outbound = order[code to Direction.OUTBOUND] ?: order[code to Direction.RETURN].orEmpty()
            val origin = outbound.firstOrNull()?.let { stops.getValue(it).name }.orEmpty()
            val destination = outbound.lastOrNull()?.let { stops.getValue(it).name }.orEmpty()
            LineEntity(
                mode = mode,
                code = code,
                displayCode = route.displayCode,
                color = route.color,
                name = if (feed == RailFeed.METRO || feed == RailFeed.IZBAN) "$origin – $destination" else route.name,
                searchText = TurkishText.fold("${route.displayCode} $code ${route.name} $origin $destination ${MODE_WORDS[feed.mode]}"),
                routeDescription = "",
                notes = "",
                origin = origin,
                destination = destination,
            )
        }

        val stopEntities = stops.filterKeys { id -> order.values.any { id in it } }.map { (id, stop) ->
            StopEntity(mode, feed.stopPrefix + id, stop.name, TurkishText.fold("${stop.name} ${MODE_WORDS[feed.mode]}"), stop.location.latitude, stop.location.longitude)
        }
        val stopLines = order.flatMap { (key, ids) -> ids.map { StopLineEntity(mode, feed.stopPrefix + it, key.first) } }.distinct()

        val departures = resolved
            .flatMap { trip -> trip.trip.days.map { day -> Triple(trip, day, trip.calls.first().minute) } }
            .groupBy { (trip, day, _) -> Triple(trip.trip.lineCode, day, trip.direction) }
            .flatMap { (key, rows) ->
                rows.distinctBy { it.third }.sortedBy { it.third }.mapIndexed { i, (trip, _, minute) ->
                    DepartureEntity(
                        mode = mode,
                        lineCode = key.first,
                        serviceDay = key.second.ordinal,
                        direction = key.third.datasetCode,
                        sequence = i,
                        minutesAfterMidnight = minute,
                        wheelchairAccessible = trip.trip.wheelchair,
                        bikeRack = false,
                        electric = true,
                    )
                }
            }

        val stopDepartures = resolved.flatMap { trip ->
            val headsign = trip.trip.headsign.ifBlank { stops.getValue(trip.calls.last().stopId).name }
            trip.calls.dropLast(1).flatMap { call ->
                trip.trip.days.map { day ->
                    StopDepartureEntity(
                        mode = mode,
                        stopCode = feed.stopPrefix + call.stopId,
                        lineCode = trip.trip.lineCode,
                        direction = trip.direction.datasetCode,
                        serviceDay = day.ordinal,
                        minutesAfterMidnight = call.minute,
                        headsign = headsign,
                    )
                }
            }
        }.distinctBy { listOf(it.stopCode, it.lineCode, it.direction, it.serviceDay, it.minutesAfterMidnight) }

        warnings += issues.summary()
        val meta = listOf(
            MetaEntity("$mode.imported_at", now.toString()),
            MetaEntity("$mode.service_end", serviceEnd?.toString().orEmpty()),
        )
        val data = StaticDataSet(
            mode = mode,
            lines = lines,
            stops = stopEntities,
            stopLines = stopLines,
            lineStops = lineStops,
            shapes = shapes,
            departures = departures,
            announcements = emptyList(),
            connections = emptyList(),
            meta = meta,
            stopDepartures = stopDepartures,
        )
        val counts = linkedMapOf(
            "lines" to lines.size,
            "stations" to stopEntities.size,
            "ordered line stops" to lineStops.size,
            "shapes" to shapes.size,
            "trips" to resolved.size,
            "line departures" to departures.size,
            "station departures" to stopDepartures.size,
        )
        ImportResult(data, ImportReport(counts, warnings))
    }

    /** "24:40:00" → 1480. Seconds are dropped. */
    internal fun parseMinutes(time: String): Int? {
        val parts = time.trim().split(':')
        if (parts.size < 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        return hours * 60 + minutes
    }

    /**
     * One stop order consistent with every trip: a topological sort of "comes before" edges,
     * breaking ties (and cycles, on ring lines) by first appearance in the longest trip.
     */
    internal fun mergeStopOrder(sequences: List<List<String>>): List<String> {
        val rank = LinkedHashMap<String, Int>()
        sequences.sortedByDescending { it.size }.forEach { seq -> seq.forEach { rank.putIfAbsent(it, rank.size) } }
        val next = HashMap<String, MutableSet<String>>()
        val inDegree = HashMap<String, Int>().apply { rank.keys.forEach { put(it, 0) } }
        for (seq in sequences) {
            val unique = seq.distinct()
            unique.zipWithNext().forEach { (a, b) ->
                if (next.getOrPut(a) { HashSet() }.add(b)) inDegree[b] = inDegree.getValue(b) + 1
            }
        }
        val result = ArrayList<String>(rank.size)
        val done = HashSet<String>()
        val ready = PriorityQueue<String>(compareBy { rank.getValue(it) })
        inDegree.filterValues { it == 0 }.keys.forEach(ready::add)
        while (result.size < rank.size) {
            val node = ready.poll() ?: rank.keys.first { it !in done } // a cycle: take the earliest
            if (!done.add(node)) continue
            result += node
            next[node].orEmpty().forEach { b ->
                val degree = inDegree.getValue(b) - 1
                inDegree[b] = degree
                if (degree == 0 && b !in done) ready += b
            }
        }
        return result
    }

    private val TimetableZone = java.time.ZoneId.of("Europe/Istanbul")
}
