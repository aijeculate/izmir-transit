package com.aijec.izmirtransit.core.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.MonthDay

/** A public holiday, or the half-day eve (arife) before a bayram. */
data class Holiday(val name: String, val eve: Boolean = false)

/**
 * Turkey's public holidays. On them the city runs a Sunday or special "bayram" timetable:
 * İzmir Metro ran every 7.5 minutes and the Konak tram on its Sunday plan over Kurban
 * Bayramı 2023, and ESHOT runs its Sunday programme on bayram days. So a holiday picks the
 * Sunday timetable everywhere in the app. An arife is a half day with the normal timetable,
 * flagged so screens can warn that the afternoon may differ.
 *
 * The fixed national days are known for any year. The bayrams follow the Hijri calendar:
 * the dates below are from Diyanet's calendar as reported for 2026–2027, and the app
 * fetches later years from the project website ([parse]) without an update.
 */
object Holidays {
    private val FIXED = mapOf(
        MonthDay.of(1, 1) to "Yılbaşı",
        MonthDay.of(4, 23) to "Ulusal Egemenlik ve Çocuk Bayramı",
        MonthDay.of(5, 1) to "Emek ve Dayanışma Günü",
        MonthDay.of(5, 19) to "Atatürk'ü Anma, Gençlik ve Spor Bayramı",
        MonthDay.of(7, 15) to "Demokrasi ve Millî Birlik Günü",
        MonthDay.of(8, 30) to "Zafer Bayramı",
        MonthDay.of(10, 29) to "Cumhuriyet Bayramı",
    )

    /** A bayram: its first day and length; the day before is its arife. */
    @Serializable
    data class Span(val name: String, val start: String, val days: Int)

    private val BUILT_IN = listOf(
        Span("Ramazan Bayramı", "2026-03-20", 3),
        Span("Kurban Bayramı", "2026-05-27", 4),
        Span("Ramazan Bayramı", "2027-03-09", 3),
        Span("Kurban Bayramı", "2027-05-16", 4),
    )

    @Volatile private var spans: List<Span> = BUILT_IN

    /** Adds bayrams from the website; the built-in ones stay, so a bad file can't remove them. */
    fun update(extra: List<Span>) {
        spans = (BUILT_IN + extra).distinctBy { it.start }
    }

    fun on(date: LocalDate): Holiday? {
        for (span in spans) {
            val start = runCatching { LocalDate.parse(span.start) }.getOrNull() ?: continue
            if (!date.isBefore(start) && date.isBefore(start.plusDays(span.days.toLong()))) return Holiday(span.name)
            if (date == start.minusDays(1)) return Holiday("${span.name} arifesi", eve = true)
        }
        FIXED[MonthDay.from(date)]?.let { return Holiday(it) }
        // Cumhuriyet Bayramı's eve is a half day too.
        if (MonthDay.from(date) == MonthDay.of(10, 28)) return Holiday("Cumhuriyet Bayramı arifesi", eve = true)
        return null
    }

    @Serializable
    private data class File(val bayrams: List<Span> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** docs/holidays.json on the project website. */
    fun parse(text: String): List<Span> = json.decodeFromString(File.serializer(), text).bayrams
        .filter { runCatching { LocalDate.parse(it.start) }.isSuccess && it.days in 1..5 }

    const val URL = "https://aijeculate.github.io/izmir-transit/holidays.json"
}
