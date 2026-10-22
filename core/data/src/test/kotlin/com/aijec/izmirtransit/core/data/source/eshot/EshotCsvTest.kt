package com.aijec.izmirtransit.core.data.source.eshot

import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.geo.LatLng
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Inputs mirror the real files' header names, delimiter and quirks (research/FINDINGS.md).
class EshotCsvTest {
    private val issues = ImportIssues()

    @Test
    fun `stops parse line lists and reject coordinates outside Izmir`() {
        val csv = """
            DURAK_ID;DURAK_ADI;ENLEM;BOYLAM;DURAKTAN_GECEN_HATLAR
            10005;Bahribaba;38.41526836260150;27.12763952722090;32
            10007;Bahribaba;38.415144105211;27.12772009127190;29-30
            10010;Boş;38,4152;27,1277;
            99999;Ghost;0;0;5
        """.trimIndent()
        val stops = EshotCsv.parseStops(csv.reader(), issues)

        assertEquals(listOf("10005", "10007", "10010"), stops.map { it.code })
        assertEquals(listOf("29", "30"), stops[1].lineCodes)
        assertEquals(emptyList(), stops[2].lineCodes)
        assertEquals(38.4152, stops[2].location.latitude, 1e-9) // comma decimal accepted
        assertEquals(1, issues.count("stop with bad coordinates"))
    }

    @Test
    fun `shapes read latitude from ENLEM even though BOYLAM comes first`() {
        val csv = """
            HAT_NO;YON;BOYLAM;ENLEM
            5;1;26.99;38.3921
            5;1;26.99;38.3919
            5;2;27.0;38.40
        """.trimIndent()
        val shapes = EshotCsv.parseShapes(csv.reader(), issues)

        assertEquals(listOf(LatLng(38.3921, 26.99), LatLng(38.3919, 26.99)), shapes["5" to Direction.OUTBOUND])
        assertEquals(1, shapes["5" to Direction.RETURN]?.size)
    }

    @Test
    fun `timetable dedupes day copies, keeps the majority and splits directions`() {
        val header = "HAT_NO;TARIFE_ID;GIDIS_SAATI;DONUS_SAATI;SIRA;GIDIS_ENGELLI_DESTEGI;DONUS_ENGELLI_DESTEGI;BISIKLETLI_GIDIS;BISIKLETLI_DONUS;GIDIS_ELEKTRIKLI_OTOBUS;DONUS_ELEKTRIKLI_OTOBUS"
        val weekdayBlock = { returnTime: String ->
            listOf(
                "5;1;06:00;06:45;1;True;True;True;True;False;False",
                "5;1;20:20;$returnTime;2;True;True;True;True;False;False",
                "5;1;;23:40;3;False;True;False;True;False;True",
            )
        }
        // Five weekday copies; two of them disagree on one time, like the real file.
        val rows = weekdayBlock("20:25") + weekdayBlock("20:25") + weekdayBlock("20:30") + weekdayBlock("20:30") + weekdayBlock("20:30")
        val csv = (listOf(header) + rows + "5;3;07:00;07:45;1;True;True;True;True;False;False" + "5;9;07:00;;1;True;True;True;True;False;False")
            .joinToString("\r\n")

        val departures = EshotCsv.parseTimetable(csv.reader(), issues)
        val weekdayOut = departures.filter { it.serviceDay == ServiceDay.WEEKDAY && it.direction == Direction.OUTBOUND }
        val weekdayBack = departures.filter { it.serviceDay == ServiceDay.WEEKDAY && it.direction == Direction.RETURN }

        assertEquals(listOf(360, 1220), weekdayOut.map { it.minutesAfterMidnight })
        assertEquals(listOf(405, 1230, 1420), weekdayBack.map { it.minutesAfterMidnight })
        assertTrue(weekdayBack.last().electric)
        assertEquals(1, departures.count { it.serviceDay == ServiceDay.SUNDAY && it.direction == Direction.OUTBOUND })
        assertEquals(1, issues.count("timetable departure differs between day copies (kept majority)"))
        assertEquals(1, issues.count("unknown TARIFE_ID"))
    }

    @Test
    fun `after-midnight departures continue the same service day`() {
        val csv = """
            HAT_NO;TARIFE_ID;GIDIS_SAATI;DONUS_SAATI;SIRA
            6;1;23:35;23:50;1
            6;1;00:05;00:20;2
        """.trimIndent()
        val outbound = EshotCsv.parseTimetable(csv.reader(), issues).filter { it.direction == Direction.OUTBOUND }
        assertEquals(listOf(23 * 60 + 35, 24 * 60 + 5), outbound.map { it.minutesAfterMidnight })
    }

    @Test
    fun `night lines belong to the service day they end`() {
        // 910 (Gaziemir - Konak) runs only at night, as in the 22 Oct 2026 file.
        val csv = """
            HAT_NO;TARIFE_ID;GIDIS_SAATI;DONUS_SAATI;SIRA
            910;1;00:15;05:00;1
            910;1;02:00;01:00;2
            910;1;04:00;03:00;3
        """.trimIndent()
        val departures = EshotCsv.parseTimetable(csv.reader(), issues)
        assertEquals(listOf(24 * 60 + 15, 26 * 60, 4 * 60), departures.filter { it.direction == Direction.OUTBOUND }.map { it.minutesAfterMidnight })
        assertEquals(listOf(5 * 60, 25 * 60, 27 * 60), departures.filter { it.direction == Direction.RETURN }.map { it.minutesAfterMidnight })
    }

    @Test
    fun `lines keep quoted notes containing the delimiter`() {
        val csv = "HAT_NO;HAT_ADI;GUZERGAH_ACIKLAMA;ACIKLAMA;HAT_BASLANGIC;HAT_BITIS\r\n" +
            "799;ÇATALCA - CUMAOVASI AKTARMA;AKÇAKÖY;\"Hafta içi ve cumartesi günleri;Çatalca'dan\";ÇATALCA;CUMAOVASI AKTARMA\r\n"
        val line = EshotCsv.parseLines(csv.reader(), issues).single()
        assertEquals("Hafta içi ve cumartesi günleri;Çatalca'dan", line.notes)
        assertEquals("CUMAOVASI AKTARMA", line.destination)
    }

    @Test
    fun `announcements parse local date-times`() {
        val csv = "HAT_NO;BASLIK;BASLAMA_TARIHI;BITIS_TARIHI\r\n" +
            "33;33 NO.LU HAT GEÇİCİ GÜZERGAH DEĞİŞİKLİĞİ;2026-10-02 00:00:00;2026-11-01 23:00:00\r\n" +
            "42;Bad date;yesterday;\r\n"
        val announcements = EshotCsv.parseAnnouncements(csv.reader(), issues)
        assertEquals(LocalDateTime.of(2026, 10, 2, 0, 0), announcements[0].startsAt)
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 0), announcements[0].endsAt)
        assertEquals(null, announcements[1].startsAt)
        assertEquals(1, issues.count("bad announcement date"))
    }
}
