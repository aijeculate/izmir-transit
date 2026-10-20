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
import java.util.zip.ZipFile

/**
 * İZBAN's current timetable, from the "Sefer Saatleri" page on izban.com.tr. The operator's
 * GTFS feed is a 2023 snapshot, but the website is kept up to date.
 *
 * The page answers one question at a time: departure and arrival times between two stations
 * on one day, with no train numbers or destinations. [OtherModesDownloader] asks it for
 * every adjacent pair of stations in both directions and saves the answers; [build] then
 * chains the legs back into whole trips (a train arriving at a station leaves again within
 * [MAX_DWELL_MINUTES]), which gives each departure its destination.
 *
 * The page lists stations in travel order, Aliağa to Sağlık, so southbound is outbound,
 * as before. Station coordinates come from the old GTFS feed, matched by name.
 */
object IzbanImporter {
    private val MODE = TransitMode.IZBAN.name
    const val PAGE_URL = "https://www.izban.com.tr/Sayfalar/SeferSaatleri.aspx?MenuId=24"

    /** The page's day ids: Monday stands for every weekday. */
    val DAYS = mapOf(ServiceDay.WEEKDAY to 1, ServiceDay.SATURDAY to 6, ServiceDay.SUNDAY to 7)

    const val STATIONS_FILE = "stations.tsv"
    fun legFile(dayId: Int, from: Int, to: Int) = "times_${dayId}_${from}_$to.tsv"

    /** Times are rounded to the minute, so a through train's dwell reads as 0–2 minutes. */
    private const val MAX_DWELL_MINUTES = 2

    // --- Page parsing (also used by the downloader) ---

    /** The departure-station dropdown: (station id, name) in travel order. */
    fun parseStations(html: String): List<Pair<Int, String>> {
        val select = Regex("""drpHareketDur".*?</select>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: return emptyList()
        return Regex("""<option[^>]*value="(\d+)"[^>]*>([^<]*)<""").findAll(select)
            .map { it.groupValues[1].toInt() to unescape(it.groupValues[2]).trim() }
            .toList()
    }

    /** The result table's (departure, arrival) rows as "HH:MM"; null if the page has no result table. */
    fun parseLegTimes(html: String): List<Pair<String, String>>? {
        val start = html.indexOf("Varış Saati").takeIf { it >= 0 } ?: return null
        return Regex("""<td[^>]*>\s*(\d{1,2}:\d{2})\s*</td>\s*<td[^>]*>\s*(\d{1,2}:\d{2})\s*</td>""")
            .findAll(html, start)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /** ASP.NET hidden form fields that every postback must send back. */
    fun hiddenFields(html: String): Map<String, String> =
        listOf("__VIEWSTATE", "__VIEWSTATEGENERATOR", "__EVENTVALIDATION").associateWith { name ->
            Regex("""id="$name" value="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        }

    private fun unescape(text: String): String = text
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")

    // --- Trip reconstruction ---

    /** One stop of a reconstructed trip: index into the travel-order station list. */
    internal data class Call(val station: Int, val minute: Int)

    /**
     * Rebuilds trips from per-leg times. [legs] holds, for each i, the (departure from
     * station i, arrival at station i + 1) pairs, all on the service day's clock. Each trip's
     * calls end with its terminus (where it only arrives).
     */
    internal fun chainTrips(legs: List<List<Pair<Int, Int>>>): List<List<Call>> {
        class Running(val calls: MutableList<Call>, var arrival: Int)
        val finished = ArrayList<List<Call>>()
        var arriving = emptyList<Running>()
        for ((i, leg) in legs.withIndex()) {
            val waiting = arriving.sortedBy { it.arrival }.toMutableList()
            val next = ArrayList<Running>()
            for ((departure, arrival) in leg.sortedBy { it.first }) {
                val through = waiting.firstOrNull { departure in it.arrival..it.arrival + MAX_DWELL_MINUTES }
                val trip = if (through != null) {
                    waiting.remove(through)
                    through
                } else {
                    Running(ArrayList(), 0)
                }
                trip.calls += Call(i, departure)
                trip.arrival = arrival
                next += trip
            }
            // Trains that didn't leave again end here.
            waiting.forEach { finished += it.calls + Call(i, it.arrival) }
            arriving = next
        }
        arriving.forEach { finished += it.calls + Call(legs.size, it.arrival) }
        return finished
    }

    /** "00:05" → 1445: the page lists a day's after-midnight trains first, like the ferries. */
    private fun serviceMinute(time: String): Int? =
        RailImporter.parseMinutes(time)?.let { if (it < 4 * 60) it + 24 * 60 else it }

    // --- Import ---

    fun build(dir: File, gtfsZip: File, now: Instant = Instant.now()): ImportResult {
        val issues = ImportIssues()
        val warnings = mutableListOf<String>()

        val stations = File(dir, STATIONS_FILE).readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val (id, name) = line.split('\t', limit = 2)
            id.toInt() to name
        }
        require(stations.size >= 2) { "İZBAN station list is empty" }

        val coordinates = gtfsCoordinates(gtfsZip)
        val located = stations.mapNotNull { (id, name) ->
            val folded = TurkishText.fold(name)
            val location = coordinates[folded]
                ?: coordinates.entries.firstOrNull { folded.startsWith(it.key) || it.key.startsWith(folded) }?.value
            if (location == null) issues.add("İZBAN station without coordinates (left out)", name)
            location?.let { Triple(id, name, it) }
        }
        val ids = located.map { it.first }
        val names = located.associate { it.first to it.second }
        val locations = located.associate { it.first to it.third }

        val trips = ArrayList<Triple<ServiceDay, Direction, List<Call>>>()
        for ((day, dayId) in DAYS) for (direction in Direction.entries) {
            val order = if (direction == Direction.OUTBOUND) ids else ids.reversed()
            val legs = order.zipWithNext().map { (from, to) ->
                val file = File(dir, legFile(dayId, from, to))
                if (!file.exists()) {
                    issues.add("missing leg", file.name)
                    emptyList()
                } else {
                    file.readLines(Charsets.UTF_8).mapNotNull { line ->
                        val parts = line.split('\t')
                        val departure = parts.getOrNull(0)?.let(::serviceMinute)
                        var arrival = parts.getOrNull(1)?.let(::serviceMinute)
                        if (departure != null && arrival != null && arrival < departure) arrival += 24 * 60
                        if (departure == null || arrival == null) null.also { if (line.isNotBlank()) issues.add("unreadable time", line) }
                        else departure to arrival
                    }
                }
            }
            chainTrips(legs).forEach { calls ->
                // Map list positions back to station ids for this direction.
                trips += Triple(day, direction, calls.map { Call(order[it.station], it.minute) })
            }
        }

        val lineCode = RailImporter.IZBAN_LINE
        val origin = names.getValue(ids.first())
        val destination = names.getValue(ids.last())
        val line = LineEntity(
            mode = MODE,
            code = lineCode,
            displayCode = "İZBAN",
            color = RailImporter.IZBAN_COLOR,
            name = "$origin – $destination",
            searchText = TurkishText.fold("izban İZBAN banliyo tren $origin $destination"),
            routeDescription = "",
            notes = "",
            origin = origin,
            destination = destination,
        )
        val stops = ids.map { id ->
            val location = locations.getValue(id)
            StopEntity(MODE, "I$id", names.getValue(id), TurkishText.fold("${names.getValue(id)} izban banliyo tren"), location.latitude, location.longitude)
        }
        val stopLines = ids.map { StopLineEntity(MODE, "I$it", lineCode) }
        val lineStops = Direction.entries.flatMap { direction ->
            val order = if (direction == Direction.OUTBOUND) ids else ids.reversed()
            order.mapIndexed { i, id -> LineStopEntity(MODE, lineCode, direction.datasetCode, i, "I$id", LineStopEntity.SOURCE_GTFS) }
        }
        val shapes = Direction.entries.map { direction ->
            val points = (if (direction == Direction.OUTBOUND) ids else ids.reversed()).map { locations.getValue(it) }
            ShapeEntity(MODE, lineCode, direction.datasetCode, PolylineCodec.encode(points), MeasuredPolyline(points).lengthMeters)
        }
        val departures = trips
            .groupBy({ it.first to it.second }, { it.third.first().minute })
            .flatMap { (key, minutes) ->
                minutes.distinct().sorted().mapIndexed { i, minute ->
                    DepartureEntity(MODE, lineCode, key.first.ordinal, key.second.datasetCode, i, minute, wheelchairAccessible = true, bikeRack = false, electric = true)
                }
            }
        val stopDepartures = trips.flatMap { (day, direction, calls) ->
            val headsign = names.getValue(calls.last().station)
            calls.dropLast(1).map { call ->
                StopDepartureEntity(
                    mode = MODE,
                    stopCode = "I${call.station}",
                    lineCode = lineCode,
                    direction = direction.datasetCode,
                    serviceDay = day.ordinal,
                    minutesAfterMidnight = call.minute,
                    headsign = headsign,
                )
            }
        }
        warnings += issues.summary()

        val data = StaticDataSet(
            mode = MODE,
            lines = listOf(line),
            stops = stops,
            stopLines = stopLines,
            lineStops = lineStops,
            shapes = shapes,
            departures = departures,
            announcements = emptyList(),
            connections = emptyList(),
            meta = listOf(
                MetaEntity("$MODE.imported_at", now.toString()),
                MetaEntity("$MODE.source", "izban.com.tr Sefer Saatleri"),
            ),
            stopDepartures = stopDepartures,
        )
        val counts = linkedMapOf(
            "stations" to stops.size,
            "trips" to trips.size,
            "line departures" to departures.size,
            "station departures" to stopDepartures.size,
        )
        return ImportResult(data, ImportReport(counts, warnings))
    }

    /** Folded station name → location, from the GTFS feed's stops.txt (lon/lat column order varies). */
    private fun gtfsCoordinates(zipFile: File): Map<String, LatLng> = ZipFile(zipFile).use { zip ->
        val entry = zip.getEntry("stops.txt") ?: error("stops.txt missing from ${zipFile.name}")
        buildMap {
            InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8).forEachCsvRow(',') { row ->
                val lat = row["stop_lat"].toDoubleOrNull()
                val lon = row["stop_lon"].toDoubleOrNull()
                if (lat != null && lon != null) put(TurkishText.fold(row["stop_name"]), LatLng(lat, lon))
            }
        }
    }
}
