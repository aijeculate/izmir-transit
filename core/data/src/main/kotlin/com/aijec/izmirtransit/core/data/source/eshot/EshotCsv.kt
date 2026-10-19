package com.aijec.izmirtransit.core.data.source.eshot

import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.csv.CsvRow
import com.aijec.izmirtransit.core.data.source.csv.forEachCsvRow
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.geo.BoundingBox
import com.aijec.izmirtransit.core.model.geo.LatLng
import java.io.Reader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Parsers for the ESHOT open-data CSVs. Columns are looked up by header name, so the
// shapes file's BOYLAM-before-ENLEM order (and any future reordering) is harmless.

private const val DELIMITER = ';'

data class EshotStop(val code: String, val name: String, val location: LatLng, val lineCodes: List<String>)

data class EshotLine(
    val code: String,
    val name: String,
    val routeDescription: String,
    val notes: String,
    val origin: String,
    val destination: String,
)

data class EshotDeparture(
    val lineCode: String,
    val serviceDay: ServiceDay,
    val direction: Direction,
    val sequence: Int,
    val minutesAfterMidnight: Int,
    val wheelchairAccessible: Boolean,
    val bikeRack: Boolean,
    val electric: Boolean,
)

data class EshotAnnouncement(val lineCode: String, val title: String, val startsAt: LocalDateTime?, val endsAt: LocalDateTime?)

data class EshotConnection(val lineCode: String, val connectionType: Int, val outboundHours: String, val returnHours: String)

object EshotCsv {
    fun parseStops(reader: Reader, issues: ImportIssues): List<EshotStop> {
        val stops = LinkedHashMap<String, EshotStop>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val code = row["DURAK_ID"]
            val location = coordinate(row["ENLEM"], row["BOYLAM"])
            when {
                code.isEmpty() -> issues.add("stop without id", row)
                location == null -> issues.add("stop with bad coordinates", row)
                code in stops -> issues.add("duplicate stop id", row)
                else -> stops[code] = EshotStop(
                    code = code,
                    name = row["DURAK_ADI"],
                    location = location,
                    lineCodes = row["DURAKTAN_GECEN_HATLAR"].split('-').map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
        }
        return stops.values.toList()
    }

    fun parseLines(reader: Reader, issues: ImportIssues): List<EshotLine> {
        val lines = LinkedHashMap<String, EshotLine>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val code = row["HAT_NO"]
            when {
                code.isEmpty() -> issues.add("line without number", row)
                code in lines -> issues.add("duplicate line", row)
                else -> lines[code] = EshotLine(
                    code = code,
                    name = row["HAT_ADI"],
                    routeDescription = row["GUZERGAH_ACIKLAMA"],
                    notes = row["ACIKLAMA"],
                    origin = row["HAT_BASLANGIC"],
                    destination = row["HAT_BITIS"],
                )
            }
        }
        return lines.values.toList()
    }

    /** Shape points per (line, direction), in file order, which is travel order. */
    fun parseShapes(reader: Reader, issues: ImportIssues): Map<Pair<String, Direction>, List<LatLng>> {
        val shapes = LinkedHashMap<Pair<String, Direction>, MutableList<LatLng>>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val direction = row["YON"].toIntOrNull()?.let(Direction::fromDatasetCode)
            val location = coordinate(row["ENLEM"], row["BOYLAM"])
            val code = row["HAT_NO"]
            when {
                code.isEmpty() || direction == null -> issues.add("shape point without line/direction", row)
                location == null -> issues.add("shape point with bad coordinates", row)
                else -> shapes.getOrPut(code to direction) { ArrayList() } += location
            }
        }
        return shapes
    }

    /**
     * The timetable file pairs one outbound and one return time per row, repeats rows
     * (tariff 1 five times, once per weekday), and leaves a time empty when a direction
     * has fewer trips. This returns one record per real departure.
     */
    fun parseTimetable(reader: Reader, issues: ImportIssues): List<EshotDeparture> {
        data class Key(val line: String, val tariff: Int, val sequence: Int)

        val copies = LinkedHashMap<Key, MutableList<CsvRow>>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val tariff = row["TARIFE_ID"].toIntOrNull()
            val sequence = row["SIRA"].toIntOrNull()
            val line = row["HAT_NO"]
            if (line.isEmpty() || tariff == null || sequence == null) {
                issues.add("timetable row without line/tariff/sequence", row)
                return@forEachCsvRow
            }
            copies.getOrPut(Key(line, tariff, sequence)) { ArrayList(5) } += row
        }
        // The weekday copies (one per day) occasionally differ by a few minutes on one or
        // two days. Without knowing which copy is which day, keep the majority version.
        val rows = copies.mapValues { (_, versions) ->
            val byTimes = versions.groupBy { it["GIDIS_SAATI"] to it["DONUS_SAATI"] }
            if (byTimes.size > 1) issues.add("timetable departure differs between day copies (kept majority)", versions.first())
            byTimes.values.maxBy { it.size }.first()
        }

        val departures = ArrayList<EshotDeparture>(rows.size * 2)
        rows.entries
            .groupBy { it.key.line to it.key.tariff }
            .forEach { (lineAndTariff, entries) ->
                val (line, tariff) = lineAndTariff
                val day = serviceDayForTariff(tariff)
                if (day == null) {
                    issues.add("unknown TARIFE_ID", entries.first().value)
                    return@forEach
                }
                val ordered = entries.sortedBy { it.key.sequence }.map { it.key.sequence to it.value }
                for (direction in Direction.entries) {
                    val timeColumn = if (direction == Direction.OUTBOUND) "GIDIS_SAATI" else "DONUS_SAATI"
                    val wheelchair = if (direction == Direction.OUTBOUND) "GIDIS_ENGELLI_DESTEGI" else "DONUS_ENGELLI_DESTEGI"
                    val bike = if (direction == Direction.OUTBOUND) "BISIKLETLI_GIDIS" else "BISIKLETLI_DONUS"
                    val electric = if (direction == Direction.OUTBOUND) "GIDIS_ELEKTRIKLI_OTOBUS" else "DONUS_ELEKTRIKLI_OTOBUS"
                    var previous = -1
                    var dayOffset = 0
                    for ((sequence, row) in ordered) {
                        val raw = row[timeColumn]
                        if (raw.isEmpty()) continue
                        val minutes = parseClock(raw)
                        if (minutes == null) {
                            issues.add("bad departure time", row)
                            continue
                        }
                        // "00:05" after "23:40" is a late trip of the same service day.
                        if (dayOffset == 0 && previous >= LATE_EVENING && minutes < EARLY_MORNING) {
                            dayOffset = MINUTES_PER_DAY
                        }
                        val adjusted = minutes + dayOffset
                        previous = adjusted
                        departures += EshotDeparture(
                            lineCode = line,
                            serviceDay = day,
                            direction = direction,
                            sequence = sequence,
                            minutesAfterMidnight = adjusted,
                            wheelchairAccessible = row[wheelchair].toBooleanFlag(),
                            bikeRack = row[bike].toBooleanFlag(),
                            electric = row[electric].toBooleanFlag(),
                        )
                    }
                }
            }
        return departures
    }

    fun parseAnnouncements(reader: Reader, issues: ImportIssues): List<EshotAnnouncement> {
        val announcements = ArrayList<EshotAnnouncement>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val code = row["HAT_NO"]
            val title = row["BASLIK"]
            if (code.isEmpty() || title.isEmpty()) {
                issues.add("announcement without line/title", row)
                return@forEachCsvRow
            }
            announcements += EshotAnnouncement(
                lineCode = code,
                title = title,
                startsAt = parseDateTime(row["BASLAMA_TARIHI"]) { issues.add("bad announcement date", row) },
                endsAt = parseDateTime(row["BITIS_TARIHI"]) { issues.add("bad announcement date", row) },
            )
        }
        return announcements
    }

    fun parseConnections(reader: Reader, issues: ImportIssues): List<EshotConnection> {
        val connections = LinkedHashMap<Pair<String, Int>, EshotConnection>()
        reader.forEachCsvRow(DELIMITER) { row ->
            val code = row["HAT_NO"]
            val type = row["BAGLANTI_TIP_ID"].toIntOrNull()
            if (code.isEmpty() || type == null) {
                issues.add("connection without line/type", row)
                return@forEachCsvRow
            }
            connections[code to type] = EshotConnection(
                lineCode = code,
                connectionType = type,
                outboundHours = row["GIDIS_CALISMA_SAATI"],
                returnHours = row["DONUS_CALISMA_SAATI"],
            )
        }
        return connections.values.toList()
    }

    /** TARIFE_ID 1/2/3. Inferred from line and trip counts against GTFS; see research/FINDINGS.md. */
    fun serviceDayForTariff(tariff: Int): ServiceDay? = when (tariff) {
        1 -> ServiceDay.WEEKDAY
        2 -> ServiceDay.SATURDAY
        3 -> ServiceDay.SUNDAY
        else -> null
    }

    private const val MINUTES_PER_DAY = 24 * 60
    private const val LATE_EVENING = 20 * 60
    private const val EARLY_MORNING = 4 * 60

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** "06:45" → 405. */
    fun parseClock(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size < 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..47 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    private inline fun parseDateTime(text: String, onError: () -> Unit): LocalDateTime? {
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text, DATE_TIME) }.getOrElse {
            onError()
            null
        }
    }
}

/** Parses a decimal that may use a comma or a dot. Never locale-dependent. */
internal fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

internal fun coordinate(latitude: String, longitude: String): LatLng? {
    val lat = parseDecimal(latitude) ?: return null
    val lon = parseDecimal(longitude) ?: return null
    return LatLng(lat, lon).takeIf { it in BoundingBox.IZMIR }
}

private fun String.toBooleanFlag(): Boolean = equals("true", ignoreCase = true) || this == "1"
