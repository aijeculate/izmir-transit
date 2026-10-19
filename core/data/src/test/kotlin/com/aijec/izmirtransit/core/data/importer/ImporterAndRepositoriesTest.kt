package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Imports a tiny dataset into an in-memory Room database and reads it back through the repositories. */
class ImporterAndRepositoriesTest {
    private val database = JvmDatabases.inMemory()
    private val lines = LineRepository(database)
    private val stops = StopRepository(database)
    private val timetable = TimetableRepository(database)

    // Line 5 runs east along lat 38.40 from lon 27.10 to 27.13 and back.
    private val sources = EshotSources(
        stops = {
            """
            DURAK_ID;DURAK_ADI;ENLEM;BOYLAM;DURAKTAN_GECEN_HATLAR
            300;Üçkuyular İskele;38.4001;27.13;5
            100;Narlıdere;38.4001;27.10;5-6
            200;Şirinyer;38.4001;27.115;5
            900;Uzak Durak;38.45;27.20;5
            """.trimIndent().reader()
        },
        lines = {
            """
            HAT_NO;HAT_ADI;GUZERGAH_ACIKLAMA;ACIKLAMA;HAT_BASLANGIC;HAT_BITIS
            5;NARLIDERE - ÜÇKUYULAR İSKELE;MİTHAT PAŞA CAD.;;NARLIDERE;ÜÇKUYULAR İSKELE
            50;KONAK - BORNOVA;;;KONAK;BORNOVA
            6;ARIKENT - F.ALTAY;;;ARIKENT;F.ALTAY
            """.trimIndent().reader()
        },
        shapes = {
            """
            HAT_NO;YON;BOYLAM;ENLEM
            5;1;27.10;38.40
            5;1;27.115;38.40
            5;1;27.13;38.40
            5;2;27.13;38.40
            5;2;27.10;38.40
            """.trimIndent().reader()
        },
        timetable = {
            """
            HAT_NO;TARIFE_ID;GIDIS_SAATI;DONUS_SAATI;SIRA
            5;1;06:00;06:45;1
            5;1;07:30;08:15;2
            5;2;09:00;;1
            """.trimIndent().reader()
        },
        announcements = {
            """
            HAT_NO;BASLIK;BASLAMA_TARIHI;BITIS_TARIHI
            5;Geçici güzergah;2026-10-01 00:00:00;2026-10-30 00:00:00
            5;Eski duyuru;2025-01-01 00:00:00;2025-02-01 00:00:00
            """.trimIndent().reader()
        },
        connections = { "BAGLANTI_TIP_ID;HAT_NO;GIDIS_CALISMA_SAATI;DONUS_CALISMA_SAATI\r\n1;5;06:00 - 23:00;06:45 - 23:40".reader() },
        gtfsZip = null,
    )

    private val result = EshotImporter.build(sources, now = Instant.parse("2026-10-20T09:00:00Z"))

    init {
        runBlocking { EshotImporter.importInto(database, result) }
    }

    @AfterTest
    fun close() = database.close()

    @Test
    fun `report counts and warns about the missing GTFS feed`() {
        assertEquals(3, result.report.counts["lines"])
        assertEquals(4, result.report.counts["stops"])
        assertTrue(result.report.warnings.any { "No GTFS feed" in it })
    }

    @Test
    fun `line search is Turkish-aware and ranks the exact number first`() = runBlocking {
        assertEquals(listOf("5"), lines.search("uckuyular").map { it.code })
        assertEquals(listOf("5"), lines.search("ÜÇKUYULAR iskele").map { it.code })
        assertEquals(listOf("5", "50"), lines.search("5").map { it.code })
        assertEquals(emptyList(), lines.search("  "))
    }

    @Test
    fun `stop search finds names regardless of case and diacritics`() = runBlocking {
        assertEquals(listOf("200"), stops.search("sirinyer").map { it.code })
        assertEquals(listOf("100"), stops.search("NARLIDERE").map { it.code })
        assertEquals(listOf("300"), stops.search("300").map { it.code })
    }

    @Test
    fun `stop order is derived from the shape when GTFS is absent`() = runBlocking {
        val routes = lines.routes("5").associateBy { it.direction }
        assertEquals(listOf("100", "200", "300"), routes.getValue(Direction.OUTBOUND).stops.map { it.code })
        assertEquals(listOf("300", "200", "100"), routes.getValue(Direction.RETURN).stops.map { it.code })
        // The straight three-point shape simplifies to its two endpoints.
        assertEquals(listOf(LatLng(38.40, 27.10), LatLng(38.40, 27.13)), routes.getValue(Direction.OUTBOUND).shape)
    }

    @Test
    fun `nearby stops are sorted by distance and limited to the radius`() = runBlocking {
        val nearby = stops.nearby(LatLng(38.4001, 27.113), radiusMeters = 1500.0)
        assertEquals(listOf("200", "100", "300"), nearby.map { it.stop.code })
        assertTrue(nearby.first().distanceMeters < 200)
    }

    @Test
    fun `lines at a stop come from the stop's line list`() = runBlocking {
        assertEquals(listOf("5", "6"), stops.linesAtStop("100").map { it.code })
    }

    @Test
    fun `timetable and next departure`() = runBlocking {
        val weekdayOut = timetable.departures("5", ServiceDay.WEEKDAY, Direction.OUTBOUND)
        assertEquals(listOf(360, 450), weekdayOut.map { it.minutesAfterMidnight })
        assertEquals(450, TimetableRepository.nextDeparture(weekdayOut, LocalTime.of(6, 1))?.minutesAfterMidnight)
        assertEquals(null, TimetableRepository.nextDeparture(weekdayOut, LocalTime.of(23, 0)))
        assertEquals(emptyList(), timetable.departures("5", ServiceDay.SATURDAY, Direction.RETURN))
        assertEquals(ServiceDay.SATURDAY, TimetableRepository.serviceDayFor(LocalDate.of(2026, 10, 24)))
    }

    @Test
    fun `only announcements active now are returned`() = runBlocking {
        val active = timetable.activeAnnouncements("5", LocalDateTime.of(2026, 10, 20, 12, 0))
        assertEquals(listOf("Geçici güzergah"), active.map { it.title })
    }

    @Test
    fun `announcements for several lines at once`() = runBlocking {
        val now = LocalDateTime.of(2026, 10, 20, 12, 0)
        assertEquals(listOf("Geçici güzergah"), timetable.activeAnnouncementsForLines(listOf("5", "6"), now).map { it.title })
        assertEquals(emptyList(), timetable.activeAnnouncementsForLines(emptyList(), now))
    }

    @Test
    fun `re-import replaces data instead of duplicating it`() = runBlocking {
        EshotImporter.importInto(database, result)
        assertEquals(2, timetable.departures("5", ServiceDay.WEEKDAY, Direction.OUTBOUND).size)
        assertEquals(1, stops.search("sirinyer").size)
    }
}
