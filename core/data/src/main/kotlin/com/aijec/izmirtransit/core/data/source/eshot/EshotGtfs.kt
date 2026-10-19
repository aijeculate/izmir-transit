package com.aijec.izmirtransit.core.data.source.eshot

import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.csv.forEachCsvRow
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/** What the app takes from the ESHOT GTFS feed: the ordered stops of each route direction. */
data class GtfsStopSequences(
    /** Keyed by (route_short_name, direction_id); values are stop_ids in travel order. */
    val sequences: Map<Pair<String, Int>, List<String>>,
    val serviceStart: LocalDate?,
    val serviceEnd: LocalDate?,
) {
    fun isExpiredOn(date: LocalDate): Boolean = serviceEnd != null && date.isAfter(serviceEnd)
}

/**
 * Reads stop sequences from the GTFS zip. The feed lapses between service periods (it was
 * expired on 20 Oct 2026), but stop order changes far less often than the calendar, so the
 * sequences are still used; the caller records the expiry as a warning.
 */
object EshotGtfs {
    private val GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun readStopSequences(zipFile: File, issues: ImportIssues): GtfsStopSequences = ZipFile(zipFile).use { zip ->
        fun reader(name: String) = zip.getEntry(name)?.let { InputStreamReader(zip.getInputStream(it), Charsets.UTF_8) }
            ?: error("$name missing from ${zipFile.name}")

        // Calendar validity.
        val starts = ArrayList<LocalDate>()
        val ends = ArrayList<LocalDate>()
        reader("calendar.txt").forEachCsvRow(',') { row ->
            runCatching { LocalDate.parse(row["start_date"], GTFS_DATE) }.getOrNull()?.let(starts::add)
            runCatching { LocalDate.parse(row["end_date"], GTFS_DATE) }.getOrNull()?.let(ends::add)
        }

        val routeNames = HashMap<String, String>()
        reader("routes.txt").forEachCsvRow(',') { row ->
            routeNames[row["route_id"]] = row["route_short_name"].ifEmpty { row["route_id"] }
        }

        // trip_id -> (route short name, direction_id)
        val tripKeys = HashMap<String, Pair<String, Int>>()
        reader("trips.txt").forEachCsvRow(',') { row ->
            val route = routeNames[row["route_id"]]
            val direction = row["direction_id"].toIntOrNull() ?: 0
            if (route == null) issues.add("GTFS trip with unknown route", row) else tripKeys[row["trip_id"]] = route to direction
        }

        // Pass 1: stop count per trip.
        val stopCounts = HashMap<String, Int>(tripKeys.size * 2)
        reader("stop_times.txt").forEachCsvRow(',') { row ->
            stopCounts.merge(row["trip_id"], 1, Int::plus)
        }

        // Representative trip per route direction: the most common stop count (the regular
        // pattern rather than a short-turn or a detour), larger count on ties.
        val chosenTrips = tripKeys.entries
            .filter { stopCounts.containsKey(it.key) }
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, trips) ->
                val byCount = trips.groupBy { stopCounts.getValue(it) }
                val commonCount = byCount.entries.maxWith(compareBy({ it.value.size }, { it.key })).key
                byCount.getValue(commonCount).min()
            }
        val wanted = chosenTrips.values.toHashSet()

        // Pass 2: collect stops for the chosen trips only.
        val collected = HashMap<String, MutableList<Pair<Int, String>>>()
        reader("stop_times.txt").forEachCsvRow(',') { row ->
            val trip = row["trip_id"]
            if (trip in wanted) {
                val sequence = row["stop_sequence"].toIntOrNull()
                if (sequence == null) issues.add("GTFS stop_time without sequence", row)
                else collected.getOrPut(trip) { ArrayList() } += sequence to row["stop_id"]
            }
        }

        GtfsStopSequences(
            sequences = chosenTrips.mapValues { (_, trip) ->
                collected[trip].orEmpty().sortedBy { it.first }.map { it.second }
            },
            serviceStart = starts.minOrNull(),
            serviceEnd = ends.maxOrNull(),
        )
    }
}
