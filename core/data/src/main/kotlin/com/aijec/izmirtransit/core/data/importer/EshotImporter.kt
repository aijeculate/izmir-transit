package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.eshot.EshotCsv
import com.aijec.izmirtransit.core.data.source.eshot.EshotGtfs
import com.aijec.izmirtransit.core.data.source.eshot.EshotStop
import com.aijec.izmirtransit.core.data.source.eshot.GtfsStopSequences
import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.database.AnnouncementEntity
import com.aijec.izmirtransit.core.database.ConnectionEntity
import com.aijec.izmirtransit.core.database.DepartureEntity
import com.aijec.izmirtransit.core.database.IzmirDatabase
import com.aijec.izmirtransit.core.database.LineEntity
import com.aijec.izmirtransit.core.database.LineStopEntity
import com.aijec.izmirtransit.core.database.MetaEntity
import com.aijec.izmirtransit.core.database.ShapeEntity
import com.aijec.izmirtransit.core.database.StaticDataSet
import com.aijec.izmirtransit.core.database.StopEntity
import com.aijec.izmirtransit.core.database.StopLineEntity
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.PolylineCodec
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import com.aijec.izmirtransit.core.model.geo.simplifyPolyline
import java.io.File
import java.io.Reader
import java.time.Instant
import java.time.ZoneId

/** Inputs for one ESHOT import. Readers are opened lazily and closed by the parsers. */
class EshotSources(
    val stops: () -> Reader,
    val lines: () -> Reader,
    val shapes: () -> Reader,
    val timetable: () -> Reader,
    val announcements: () -> Reader,
    val connections: () -> Reader,
    /** Optional: without it, stop order is derived from the route shapes. */
    val gtfsZip: File?,
) {
    companion object {
        /** The six CSVs under their portal file names, as downloaded. */
        fun fromDirectory(dir: File, gtfsZip: File?): EshotSources {
            fun open(name: String): () -> Reader = { File(dir, name).reader(Charsets.UTF_8) }
            return EshotSources(
                stops = open("eshot-otobus-duraklari.csv"),
                lines = open("eshot-otobus-hatlari.csv"),
                shapes = open("eshot-otobus-hat-guzergahlari.csv"),
                timetable = open("eshot-otobus-hareketsaatleri.csv"),
                announcements = open("eshot-otobus-hat-duyurulari.csv"),
                connections = open("eshot-otobus-baglantili-hatlar.csv"),
                gtfsZip = gtfsZip?.takeIf { it.exists() },
            )
        }
    }
}

data class ImportReport(val counts: Map<String, Int>, val warnings: List<String>) {
    override fun toString(): String = buildString {
        appendLine("Imported:")
        counts.forEach { (name, count) -> appendLine("  %-22s %,d".format(name, count)) }
        if (warnings.isNotEmpty()) {
            appendLine("Warnings:")
            warnings.forEach { appendLine("  - $it") }
        }
    }
}

data class ImportResult(val data: StaticDataSet, val report: ImportReport)

/**
 * Builds the ESHOT tables from the open-data files. Pure: no database access, so the same
 * code runs in :tools:importer (to build the shipped DB) and in the app's weekly refresh.
 */
object EshotImporter {
    private val MODE = TransitMode.BUS.name
    private val ISTANBUL = ZoneId.of("Europe/Istanbul")

    /** Shapes are 4-decimal (~10 m) data; 3 m simplification only removes redundant points. */
    private const val SHAPE_TOLERANCE_METERS = 3.0

    /** A stop this close to a direction's shape is considered on it (shape fallback only). */
    private const val STOP_ON_SHAPE_METERS = 25.0

    fun build(sources: EshotSources, now: Instant = Instant.now()): ImportResult {
        val issues = ImportIssues()
        val warnings = mutableListOf<String>()

        val stops = EshotCsv.parseStops(sources.stops(), issues)
        val lines = EshotCsv.parseLines(sources.lines(), issues)
        val shapes = EshotCsv.parseShapes(sources.shapes(), issues)
        val departures = EshotCsv.parseTimetable(sources.timetable(), issues)
        val announcements = EshotCsv.parseAnnouncements(sources.announcements(), issues)
        val connections = EshotCsv.parseConnections(sources.connections(), issues)

        val stopsByCode = stops.associateBy { it.code }
        val simplifiedShapes = shapes.mapValues { (_, points) -> simplifyPolyline(points, SHAPE_TOLERANCE_METERS) }

        val gtfs = sources.gtfsZip?.let { EshotGtfs.readStopSequences(it, issues) }
        if (gtfs == null) {
            warnings += "No GTFS feed: stop order for every line comes from route shapes"
        } else if (gtfs.isExpiredOn(now.atZone(ISTANBUL).toLocalDate())) {
            warnings += "GTFS calendar ended ${gtfs.serviceEnd}; its stop order is used anyway (timetables come from the CSV)"
        }
        val lineStops = buildLineStops(lines.map { it.code }, stops, stopsByCode, shapes, gtfs, issues)

        val lineEntities = lines.map { line ->
            LineEntity(
                mode = MODE,
                code = line.code,
                displayCode = line.code,
                color = null,
                name = line.name,
                searchText = TurkishText.fold("${line.code} ${line.name} ${line.origin} ${line.destination} ${line.routeDescription}"),
                routeDescription = line.routeDescription,
                notes = line.notes,
                origin = line.origin,
                destination = line.destination,
            )
        }
        val stopEntities = stops.map { stop ->
            StopEntity(MODE, stop.code, stop.name, TurkishText.fold(stop.name), stop.location.latitude, stop.location.longitude)
        }
        val stopLineEntities = stops.flatMap { stop ->
            stop.lineCodes.distinct().map { StopLineEntity(MODE, stop.code, it) }
        }
        val shapeEntities = simplifiedShapes.map { (key, points) ->
            ShapeEntity(MODE, key.first, key.second.datasetCode, PolylineCodec.encode(points), MeasuredPolyline(points).lengthMeters)
        }
        val departureEntities = departures.map {
            DepartureEntity(
                mode = MODE,
                lineCode = it.lineCode,
                serviceDay = it.serviceDay.ordinal,
                direction = it.direction.datasetCode,
                sequence = it.sequence,
                minutesAfterMidnight = it.minutesAfterMidnight,
                wheelchairAccessible = it.wheelchairAccessible,
                bikeRack = it.bikeRack,
                electric = it.electric,
            )
        }
        val announcementEntities = announcements.map {
            AnnouncementEntity(
                mode = MODE,
                lineCode = it.lineCode,
                title = it.title,
                startsAt = it.startsAt?.toString(),
                endsAt = it.endsAt?.toString(),
            )
        }
        val connectionEntities = connections.map {
            ConnectionEntity(MODE, it.lineCode, it.connectionType, it.outboundHours, it.returnHours)
        }

        val linesWithoutShape = lines.count { line -> Direction.entries.none { (line.code to it) in shapes } }
        if (linesWithoutShape > 0) warnings += "$linesWithoutShape lines have no route shape"
        val linesWithoutTimetable = lines.map { it.code }.toSet() - departures.map { it.lineCode }.toSet()
        if (linesWithoutTimetable.isNotEmpty()) warnings += "${linesWithoutTimetable.size} lines have no timetable"
        warnings += issues.summary()

        val meta = listOf(
            MetaEntity("$MODE.imported_at", now.toString()),
            MetaEntity("$MODE.gtfs_service_end", gtfs?.serviceEnd?.toString().orEmpty()),
            MetaEntity("schema_note", "Built by EshotImporter; see research/FINDINGS.md"),
        )

        val data = StaticDataSet(
            mode = MODE,
            lines = lineEntities,
            stops = stopEntities,
            stopLines = stopLineEntities,
            lineStops = lineStops,
            shapes = shapeEntities,
            departures = departureEntities,
            announcements = announcementEntities,
            connections = connectionEntities,
            meta = meta,
        )
        val counts = linkedMapOf(
            "lines" to lineEntities.size,
            "stops" to stopEntities.size,
            "stop-line links" to stopLineEntities.size,
            "ordered line stops" to lineStops.size,
            "  from GTFS" to lineStops.count { it.source == LineStopEntity.SOURCE_GTFS },
            "  from shapes" to lineStops.count { it.source == LineStopEntity.SOURCE_SHAPE },
            "shapes" to shapeEntities.size,
            "shape points (raw)" to shapes.values.sumOf { it.size },
            "shape points (kept)" to simplifiedShapes.values.sumOf { it.size },
            "departures" to departureEntities.size,
            "announcements" to announcementEntities.size,
            "connections" to connectionEntities.size,
        )
        return ImportResult(data, ImportReport(counts, warnings))
    }

    suspend fun importInto(database: IzmirDatabase, result: ImportResult) {
        database.staticDataDao().replaceMode(result.data)
    }

    /**
     * Ordered stops per line direction. GTFS gives true order but numbers directions 0/1
     * with no documented link to YON 1/2, so each route's GTFS directions are matched to
     * the CSV shapes by comparing endpoints. Lines missing from GTFS get their order from
     * projecting their stops onto the shape.
     */
    private fun buildLineStops(
        lineCodes: List<String>,
        stops: List<EshotStop>,
        stopsByCode: Map<String, EshotStop>,
        shapes: Map<Pair<String, Direction>, List<LatLng>>,
        gtfs: GtfsStopSequences?,
        issues: ImportIssues,
    ): List<LineStopEntity> {
        val stopsByLine = HashMap<String, MutableList<EshotStop>>()
        stops.forEach { stop -> stop.lineCodes.forEach { stopsByLine.getOrPut(it) { ArrayList() } += stop } }

        val result = ArrayList<LineStopEntity>()
        for (line in lineCodes) {
            val gtfsDirections = gtfs?.sequences.orEmpty()
                .filterKeys { it.first == line }
                .mapKeys { it.key.second }
                .mapValues { (_, ids) ->
                    ids.filter { id -> (id in stopsByCode).also { known -> if (!known) issues.add("GTFS stop not in stops CSV", "$line:$id") } }
                }
                .filterValues { it.size >= 2 }
            val assignment = matchDirections(line, gtfsDirections, stopsByCode, shapes)
            assignment.forEach { (direction, gtfsDirection) ->
                issues.add("info: GTFS direction_id $gtfsDirection matched YON ${direction.datasetCode}", line)
            }

            for (direction in Direction.entries) {
                val gtfsDirection = assignment[direction]
                if (gtfsDirection != null) {
                    gtfsDirections.getValue(gtfsDirection).forEachIndexed { i, code ->
                        result += LineStopEntity(MODE, line, direction.datasetCode, i, code, LineStopEntity.SOURCE_GTFS)
                    }
                    continue
                }
                val shape = shapes[line to direction] ?: continue
                val measured = MeasuredPolyline(shape)
                stopsByLine[line].orEmpty()
                    .mapNotNull { stop ->
                        measured.project(stop.location)
                            ?.takeIf { it.distanceFromLineMeters <= STOP_ON_SHAPE_METERS }
                            ?.let { stop to it.distanceAlongMeters }
                    }
                    .sortedBy { it.second }
                    .forEachIndexed { i, (stop, _) ->
                        result += LineStopEntity(MODE, line, direction.datasetCode, i, stop.code, LineStopEntity.SOURCE_SHAPE)
                    }
                if (gtfs != null) issues.add("direction ordered from shape (not in GTFS)", "$line/${direction.datasetCode}")
            }
        }
        return result
    }

    /** Maps dataset Direction → GTFS direction_id by endpoint distance to the CSV shapes. */
    private fun matchDirections(
        line: String,
        gtfsDirections: Map<Int, List<String>>,
        stopsByCode: Map<String, EshotStop>,
        shapes: Map<Pair<String, Direction>, List<LatLng>>,
    ): Map<Direction, Int> {
        if (gtfsDirections.isEmpty()) return emptyMap()
        fun cost(gtfsDirection: Int, direction: Direction): Double {
            val shape = shapes[line to direction] ?: return Double.MAX_VALUE / 4
            val ids = gtfsDirections.getValue(gtfsDirection)
            val first = stopsByCode.getValue(ids.first()).location
            val last = stopsByCode.getValue(ids.last()).location
            return distanceMeters(first, shape.first()) + distanceMeters(last, shape.last())
        }
        val ids = gtfsDirections.keys.sorted()
        return if (ids.size == 1) {
            val only = ids.single()
            mapOf(Direction.entries.minBy { cost(only, it) } to only)
        } else {
            val (a, b) = ids[0] to ids[1]
            val straight = cost(a, Direction.OUTBOUND) + cost(b, Direction.RETURN)
            val swapped = cost(a, Direction.RETURN) + cost(b, Direction.OUTBOUND)
            if (straight <= swapped) mapOf(Direction.OUTBOUND to a, Direction.RETURN to b)
            else mapOf(Direction.OUTBOUND to b, Direction.RETURN to a)
        }
    }
}
