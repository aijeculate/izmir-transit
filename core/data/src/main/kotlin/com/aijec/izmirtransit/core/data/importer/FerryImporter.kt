package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.source.ImportIssues
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
internal data class PierDto(
    @SerialName("IskeleId") val id: Int,
    @SerialName("Adi") val name: String,
    @SerialName("Enlem") val latitude: Double,
    @SerialName("Boylam") val longitude: Double,
    @SerialName("AktifMi") val active: Boolean = true,
)

@Serializable
internal data class PierDayDto(val seferler: List<SailingDto> = emptyList())

@Serializable
internal data class SailingDto(
    @SerialName("KalkisSaati") val departure: String,
    @SerialName("VarisYerleri") val destinations: List<DestinationDto> = emptyList(),
    @SerialName("arabaliVapurSeferi") val carFerry: Boolean = false,
)

@Serializable
internal data class DestinationDto(@SerialName("iskeleAdi") val name: String)

/**
 * Builds the İzdeniz ferry tables from the İzdeniz open API's pier timetables
 * (`iskeleler` and `iskelesefersaatleri/{pier}/{day}`), saved as files by [FerryDownloader].
 *
 * Every pair of piers a sailing connects becomes one line ("F-1-2", Konak – Karşıyaka),
 * drawn as a straight line over the water; a sailing belongs to the line of its first leg,
 * and its headsign lists every pier it calls at. Day 1 (Monday) stands for every weekday;
 * Monday to Friday differ by a sailing or two at most.
 */
object FerryImporter {
    private val MODE = TransitMode.FERRY.name
    const val COLOR = 0xFF0097D7.toInt()

    /** İzdeniz day ids used for each timetable. */
    val DAYS = mapOf(ServiceDay.WEEKDAY to 1, ServiceDay.SATURDAY to 6, ServiceDay.SUNDAY to 7)

    const val PIERS_FILE = "iskeleler.json"
    fun pierFile(pierId: Int, dayId: Int) = "pier_${pierId}_day_$dayId.json"

    private val json = Json { ignoreUnknownKeys = true }

    internal fun parsePiers(text: String): List<PierDto> = json.decodeFromString(text)

    fun build(dir: File, now: Instant = Instant.now()): ImportResult {
        val issues = ImportIssues()
        val piers = parsePiers(File(dir, PIERS_FILE).readText()).filter { it.active }
        val byName = piers.associateBy { TurkishText.fold(it.name) }

        class Sailing(val from: PierDto, val calls: List<PierDto>, val day: ServiceDay, val minute: Int)
        val sailings = ArrayList<Sailing>()
        for (pier in piers) for ((day, dayId) in DAYS) {
            val file = File(dir, pierFile(pier.id, dayId))
            if (!file.exists()) continue
            val parsed = runCatching { json.decodeFromString<PierDayDto>(file.readText()) }
                .onFailure { issues.add("unreadable pier timetable", file.name) }
                .getOrNull() ?: continue
            for (sailing in parsed.seferler) {
                val minute = RailImporter.parseMinutes(sailing.departure)
                val calls = sailing.destinations.mapNotNull { destination ->
                    byName[TurkishText.fold(destination.name)].also { if (it == null) issues.add("unknown destination pier", destination.name) }
                }.filter { it.id != pier.id }
                if (minute == null || calls.isEmpty()) {
                    issues.add("sailing without time or destination", "${pier.name} ${sailing.departure}")
                    continue
                }
                // Before 04:00 belongs to the previous evening's timetable.
                sailings += Sailing(pier, calls, day, if (minute < 4 * 60) minute + 24 * 60 else minute)
            }
        }

        fun legKey(a: PierDto, b: PierDto) = if (a.id < b.id) a to b else b to a
        fun lineCode(a: PierDto, b: PierDto) = legKey(a, b).let { (x, y) -> "F-${x.id}-${y.id}" }
        fun directionOf(from: PierDto, to: PierDto) = if (from.id < to.id) Direction.OUTBOUND else Direction.RETURN

        val legs = sailings.flatMap { s -> (listOf(s.from) + s.calls).zipWithNext().map { (a, b) -> legKey(a, b) } }.distinct()
        val usedPiers = legs.flatMap { listOf(it.first, it.second) }.distinctBy { it.id }

        val lines = legs.map { (a, b) ->
            LineEntity(
                mode = MODE,
                code = lineCode(a, b),
                displayCode = "Vapur",
                color = COLOR,
                name = "${a.name} – ${b.name}",
                searchText = TurkishText.fold("vapur feribot ferry iskele ${a.name} ${b.name}"),
                routeDescription = "",
                notes = "",
                origin = a.name,
                destination = b.name,
            )
        }
        val stops = usedPiers.map { pier ->
            StopEntity(MODE, "F${pier.id}", pier.name, TurkishText.fold("${pier.name} iskele vapur"), pier.latitude, pier.longitude)
        }
        val stopLines = legs.flatMap { (a, b) ->
            listOf(StopLineEntity(MODE, "F${a.id}", lineCode(a, b)), StopLineEntity(MODE, "F${b.id}", lineCode(a, b)))
        }
        val lineStops = legs.flatMap { (a, b) ->
            val code = lineCode(a, b)
            listOf(
                LineStopEntity(MODE, code, Direction.OUTBOUND.datasetCode, 0, "F${a.id}", LineStopEntity.SOURCE_GTFS),
                LineStopEntity(MODE, code, Direction.OUTBOUND.datasetCode, 1, "F${b.id}", LineStopEntity.SOURCE_GTFS),
                LineStopEntity(MODE, code, Direction.RETURN.datasetCode, 0, "F${b.id}", LineStopEntity.SOURCE_GTFS),
                LineStopEntity(MODE, code, Direction.RETURN.datasetCode, 1, "F${a.id}", LineStopEntity.SOURCE_GTFS),
            )
        }
        val shapes = legs.flatMap { (a, b) ->
            val points = listOf(LatLng(a.latitude, a.longitude), LatLng(b.latitude, b.longitude))
            val length = MeasuredPolyline(points).lengthMeters
            listOf(
                ShapeEntity(MODE, lineCode(a, b), Direction.OUTBOUND.datasetCode, PolylineCodec.encode(points), length),
                ShapeEntity(MODE, lineCode(a, b), Direction.RETURN.datasetCode, PolylineCodec.encode(points.reversed()), length),
            )
        }
        val departures = sailings
            .groupBy { Triple(lineCode(it.from, it.calls.first()), it.day, directionOf(it.from, it.calls.first())) }
            .flatMap { (key, group) ->
                group.map { it.minute }.distinct().sorted().mapIndexed { i, minute ->
                    DepartureEntity(MODE, key.first, key.second.ordinal, key.third.datasetCode, i, minute, wheelchairAccessible = false, bikeRack = false, electric = false)
                }
            }
        val stopDepartures = sailings.map { s ->
            StopDepartureEntity(
                mode = MODE,
                stopCode = "F${s.from.id}",
                lineCode = lineCode(s.from, s.calls.first()),
                direction = directionOf(s.from, s.calls.first()).datasetCode,
                serviceDay = s.day.ordinal,
                minutesAfterMidnight = s.minute,
                headsign = s.calls.joinToString(", ") { it.name },
            )
        }.distinctBy { listOf(it.stopCode, it.lineCode, it.serviceDay, it.minutesAfterMidnight, it.headsign) }

        val data = StaticDataSet(
            mode = MODE,
            lines = lines,
            stops = stops,
            stopLines = stopLines,
            lineStops = lineStops,
            shapes = shapes,
            departures = departures,
            announcements = emptyList(),
            connections = emptyList(),
            meta = listOf(MetaEntity("$MODE.imported_at", now.toString())),
            stopDepartures = stopDepartures,
        )
        val counts = linkedMapOf(
            "routes" to lines.size,
            "piers" to stops.size,
            "sailings" to sailings.size,
            "pier departures" to stopDepartures.size,
        )
        return ImportResult(data, ImportReport(counts, issues.summary()))
    }
}
