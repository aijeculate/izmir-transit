package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.text.TurkishText
import com.aijec.izmirtransit.core.model.ServiceDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/**
 * Service bands from the API behind izmirmetro.com.tr/sefer-plani ("06:30–09:00, every
 * 4 min" per line and day type), for metro and tram. They are newer than the operators'
 * GTFS times: on 23 Oct 2026 the site had the metro every 4 minutes at the weekday peak
 * where the GTFS had 5, and Sunday every 7.5 where the GTFS had 10. They give no exact
 * times, so [RailImporter] lays departures out on the bands and takes the stations and
 * running times from the GTFS.
 */
object RailFrequencies {
    private const val API = "https://metrotramapi.iztek.tr/api/v1/"

    /** One band: departures from [startMinute] (inclusive) to [endMinute], every [everyMinutes]. */
    data class Band(val lineName: String, val day: ServiceDay, val startMinute: Int, val endMinute: Int, val everyMinutes: Double)

    fun url(feed: RailFeed): String? = when (feed) {
        RailFeed.METRO -> API + "line-schedules"
        RailFeed.TRAM -> API + "tramline-schedules"
        RailFeed.IZBAN -> null
    }

    /** Saved next to the feed's GTFS zip, so the importer finds it without extra wiring. */
    fun file(feed: RailFeed, dir: File) = File(dir, "${feed.name.lowercase()}-frequencies.json")

    fun readBeside(feed: RailFeed, zip: File): List<Band>? =
        file(feed, zip.absoluteFile.parentFile).takeIf { it.isFile }?.let { parse(it.readText()) }

    /** The site's tram names; the metro has one line, M1, served by two patterns. */
    private val TRAM_LINES = mapOf(
        "konak tramvayi" to "T-KONAK",
        "karsiyaka tramvayi" to "T-KARSIYAKA",
        "karsiyaka ring tramvayi turuncu" to "T-TURUNCU",
        "cigli tramvayi dis hat kirmizi" to "T-CIGLI_DIS",
        "cigli tramvayi ic hat mavi" to "T-CIGLI_IC",
    )

    fun lineCodeFor(feed: RailFeed, siteName: String, lineCodes: List<String>): String? = when (feed) {
        RailFeed.METRO -> lineCodes.singleOrNull() ?: lineCodes.firstOrNull { it == "M1" }
        RailFeed.TRAM -> TRAM_LINES[TurkishText.fold(siteName).replace(Regex("\\s+"), " ").trim()]?.takeIf { it in lineCodes }
        RailFeed.IZBAN -> null
    }

    /** "Evka - 3 / Narlıdere Kaymakamlık" → the pattern's two end stations; null for a whole line. */
    fun terminals(siteName: String): Set<String>? =
        siteName.split(" / ").takeIf { it.size == 2 }?.map(::key)?.toSet()

    /**
     * Whether a trip from [first] to [last] runs between [ends]. Names may be shortened on
     * one side: the GTFS calls the metro's terminus "Kaymakamlık", the site "Narlıdere
     * Kaymakamlık".
     */
    fun sameEnds(ends: Set<String>, first: String, last: String): Boolean {
        fun same(a: String, b: String) = a == b || a.endsWith(b) || b.endsWith(a)
        val (a, b) = ends.toList().takeIf { it.size == 2 } ?: return false
        val f = key(first)
        val l = key(last)
        return (same(a, f) && same(b, l)) || (same(a, l) && same(b, f))
    }

    /** Station names compared without case, accents, spaces or punctuation. */
    fun key(name: String): String = TurkishText.fold(name).filter { it.isLetterOrDigit() }

    /**
     * Departure minutes on the service day's clock. Each band runs up to its end; the day's
     * last band includes its end ("until 00:20" means a 00:20 train). Times before 04:00
     * belong to the previous service day (minute 1440+).
     */
    fun departures(bands: List<Band>): List<Int> {
        val sorted = bands.sortedBy { it.startMinute }
        val out = sortedSetOf<Int>()
        sorted.forEachIndexed { i, band ->
            if (band.everyMinutes <= 0) return@forEachIndexed
            val last = i == sorted.lastIndex
            var t = band.startMinute.toDouble()
            while (t < band.endMinute || (last && t <= band.endMinute)) {
                out += t.roundToInt()
                t += band.everyMinutes
            }
        }
        return out.toList()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Band> = json.decodeFromString(Response.serializer(), text).data.mapNotNull { row ->
        val day = when (TurkishText.fold(row.dayTypeName)) {
            "hafta ici" -> ServiceDay.WEEKDAY
            "cumartesi" -> ServiceDay.SATURDAY
            "pazar" -> ServiceDay.SUNDAY
            else -> return@mapNotNull null
        }
        val start = serviceMinute(row.startTime) ?: return@mapNotNull null
        var end = serviceMinute(row.endTime) ?: return@mapNotNull null
        if (end <= start) end += 24 * 60
        Band(row.lineName, day, start, end, row.frequencyInMinutes)
    }

    private fun serviceMinute(time: String): Int? {
        val parts = time.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = h * 60 + m
        return if (h < 4) minute + 24 * 60 else minute
    }

    @Serializable
    private data class Response(val data: List<Row> = emptyList())

    @Serializable
    private data class Row(
        val lineName: String,
        val dayTypeName: String,
        val startTime: String,
        val endTime: String,
        val frequencyInMinutes: Double,
    )
}
