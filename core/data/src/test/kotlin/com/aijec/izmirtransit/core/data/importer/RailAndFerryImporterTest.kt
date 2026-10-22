package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.TransitMode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RailAndFerryImporterTest {
    private val dir = Files.createTempDirectory("rail-ferry-test").toFile()
    private val database = JvmDatabases.inMemory()
    private val now = Instant.parse("2026-10-20T10:00:00Z")

    @AfterTest
    fun tearDown() {
        database.close()
        dir.deleteRecursively()
    }

    private fun zip(name: String, files: Map<String, String>): File = File(dir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            files.forEach { (entry, text) ->
                out.putNextEntry(ZipEntry(entry))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
    }

    // İZBAN-like feed: north (A) to south (C), stops columns in İZBAN's lon-first order,
    // a short trip A→B, a full trip A→C, a northbound trip after midnight, and
    // direction_id deliberately wrong (1 for every trip).
    private fun izbanZip() = zip(
        "izban.zip",
        mapOf(
            "calendar.txt" to """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                DAYSOFWEEK,1,1,1,1,1,1,1,20220101,20250101
                WEEKDAYS,1,1,1,1,1,0,0,20220101,20250101
            """,
            "routes.txt" to """
                route_id,agency_id,route_long_name,route_short_name,route_desc,route_type,route_url,route_color,route_text_color
                10,IZB,A-B,,,2,,,
                11,IZB,C-A,,,2,,,
            """,
            "stops.txt" to """
                stop_id,stop_name,stop_lon,stop_lat
                1,Aliağa,27.0,38.8
                2,Biçerova,27.0,38.7
                3,Cumaovası,27.1,38.2
            """,
            "trips.txt" to """
                route_id,service_id,trip_headsign,trip_id,direction_id
                10,WEEKDAYS,Biçerova,t1,1
                10,DAYSOFWEEK,,t2,1
                11,DAYSOFWEEK,Aliağa,t3,1
            """,
            "stop_times.txt" to """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,06:00:00,06:00:00,1,1
                t1,06:10:00,06:10:00,2,2
                t2,07:00:00,07:00:00,1,1
                t2,07:10:30,07:10:30,2,2
                t2,07:40:00,07:40:00,3,3
                t3,24:20:00,24:20:00,3,1
                t3,24:50:00,24:50:00,2,2
                t3,25:00:00,25:00:00,1,3
            """,
        ),
    )

    @Test
    fun `rail feed becomes one line with merged station order and station boards`() = runBlocking {
        val result = RailImporter.build(RailFeed.IZBAN, izbanZip(), now)
        database.staticDataDao().replaceMode(result.data)
        val lines = LineRepository(database)
        val stops = StopRepository(database)

        val line = lines.line(RailImporter.IZBAN_LINE)!!
        assertEquals(TransitMode.IZBAN, line.mode)
        assertEquals("İZBAN", line.displayCode)
        assertEquals("Aliağa", line.origin)
        assertEquals("Cumaovası", line.destination)

        val routes = lines.routes(RailImporter.IZBAN_LINE).associateBy { it.direction }
        assertEquals(listOf("I1", "I2", "I3"), routes.getValue(Direction.OUTBOUND).stops.map { it.code })
        assertEquals(listOf("I3", "I2", "I1"), routes.getValue(Direction.RETURN).stops.map { it.code })
        assertEquals(3, routes.getValue(Direction.OUTBOUND).shape.size)

        // The short train towards Biçerova runs only the first two stations; a full one is the whole route.
        val short = lines.tripRoute(routes.getValue(Direction.OUTBOUND), "Biçerova")!!
        assertEquals(listOf("I1", "I2"), short.stops.map { it.code })
        assertEquals(2, short.shape.size)
        assertEquals(routes.getValue(Direction.OUTBOUND), lines.tripRoute(routes.getValue(Direction.OUTBOUND), "Cumaovası"))
        assertEquals(null, lines.tripRoute(routes.getValue(Direction.OUTBOUND), "Nowhere"))
        // From a station that train doesn't leave from (Biçerova is its end), no match.
        assertEquals(null, lines.tripRoute(routes.getValue(Direction.OUTBOUND), "Biçerova", fromStop = "I2"))
        assertEquals(listOf("I1", "I2"), lines.tripRoute(routes.getValue(Direction.OUTBOUND), "Biçerova", fromStop = "I1")!!.stops.map { it.code })

        // Weekday board at Biçerova: the 06:10 short trip ends there, so it isn't a departure.
        val weekday = stops.scheduledDepartures("I2", ServiceDay.WEEKDAY, 0)
        assertEquals(listOf(7 * 60 + 10, 24 * 60 + 50), weekday.map { it.minutesAfterMidnight })
        assertEquals(listOf("Cumaovası", "Aliağa"), weekday.map { it.headsign })
        assertEquals(listOf(Direction.OUTBOUND, Direction.RETURN), weekday.map { it.direction })
        // WEEKDAYS-only trips don't run on Sunday.
        val sunday = stops.scheduledDepartures("I1", ServiceDay.SUNDAY, 0)
        assertEquals(listOf(7 * 60), sunday.map { it.minutesAfterMidnight })

        val timetable = TimetableRepository(database).departures(RailImporter.IZBAN_LINE, ServiceDay.WEEKDAY, Direction.OUTBOUND)
        assertEquals(listOf(6 * 60, 7 * 60), timetable.map { it.minutesAfterMidnight })
        assertTrue(result.report.warnings.any { "calendar ended" in it })

        // Rail is found by mode word, and ranks before buses.
        assertEquals(listOf(RailImporter.IZBAN_LINE), lines.search("izban").map { it.code })
    }

    @Test
    fun `merged stop order keeps short and express trips in line`() {
        val order = RailImporter.mergeStopOrder(
            listOf(listOf("b", "c"), listOf("a", "c", "e"), listOf("a", "b", "c", "d", "e")),
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), order)
        // A ring (first stop repeated at the end) still yields every stop once.
        assertEquals(listOf("x", "y", "z"), RailImporter.mergeStopOrder(listOf(listOf("x", "y", "z", "x"))))
    }

    @Test
    fun `gtfs times past midnight stay on the service day`() {
        assertEquals(24 * 60 + 40, RailImporter.parseMinutes("24:40:00"))
        assertEquals(6 * 60 + 5, RailImporter.parseMinutes("06:05:51"))
        assertEquals(null, RailImporter.parseMinutes("x"))
    }

    @Test
    fun `ferry sailings become pier-pair routes and pier boards`() = runBlocking {
        val ferryDir = File(dir, "ferry").apply { mkdirs() }
        File(ferryDir, FerryImporter.PIERS_FILE).writeText(
            """
            [{"IskeleId":1,"Adi":"Konak","Enlem":38.4186,"Boylam":27.1258,"AktifMi":true},
             {"IskeleId":2,"Adi":"Karşıyaka","Enlem":38.4547,"Boylam":27.1205,"AktifMi":true},
             {"IskeleId":3,"Adi":"Bostanlı","Enlem":38.4519,"Boylam":27.0977,"AktifMi":true},
             {"IskeleId":9,"Adi":"Foça","Enlem":38.66,"Boylam":26.75,"AktifMi":false},
             {"IskeleId":11,"Adi":"Urla","Enlem":38.36,"Boylam":26.77,"AktifMi":true}]
            """,
        )
        File(ferryDir, FerryImporter.pierFile(1, 1)).writeText(
            """
            {"gunId":1,"seferler":[
              {"KalkisSaati":"07:30:00","VarisYerleri":[{"iskeleAdi":"Karşıyaka"}],"arabaliVapurSeferi":false,"IptalMi":null},
              {"KalkisSaati":"08:00:00","VarisYerleri":[{"iskeleAdi":"KARŞIYAKA"},{"iskeleAdi":"Bostanlı"}],"arabaliVapurSeferi":false},
              {"KalkisSaati":"00:15:00","VarisYerleri":[{"iskeleAdi":"Karşıyaka"}]}
            ]}
            """,
        )
        File(ferryDir, FerryImporter.pierFile(2, 1)).writeText(
            """{"gunId":1,"seferler":[{"KalkisSaati":"07:45:00","VarisYerleri":[{"iskeleAdi":"Konak"}]}]}""",
        )
        File(ferryDir, FerryImporter.pierFile(11, 1)).writeText("""{"gunId":1,"seferler":[]}""")

        val result = FerryImporter.build(ferryDir, now)
        database.staticDataDao().replaceMode(result.data)
        val lines = LineRepository(database)
        val stops = StopRepository(database)

        // Konak–Karşıyaka and the Karşıyaka–Bostanlı leg of the 08:00; Urla has no sailings.
        assertEquals(setOf("F-1-2", "F-2-3"), result.data.lines.map { it.code }.toSet())
        assertEquals(setOf("F1", "F2", "F3"), result.data.stops.map { it.code }.toSet())
        assertEquals("Konak – Karşıyaka", lines.line("F-1-2")!!.name)

        val konak = stops.scheduledDepartures("F1", ServiceDay.WEEKDAY, 0)
        assertEquals(listOf(7 * 60 + 30, 8 * 60, 24 * 60 + 15), konak.map { it.minutesAfterMidnight })
        assertEquals("Karşıyaka, Bostanlı", konak[1].headsign)
        assertEquals("F-1-2", konak[1].line.code)

        // "Just left": the latest earlier sailing per line and direction, within 20 minutes.
        assertEquals(listOf(8 * 60), stops.recentDepartures("F1", ServiceDay.WEEKDAY, 8 * 60 + 5).map { it.minutesAfterMidnight })
        assertEquals(listOf(7 * 60 + 30), stops.recentDepartures("F1", ServiceDay.WEEKDAY, 7 * 60 + 45).map { it.minutesAfterMidnight })
        // The 08:00 is still "now" on the board at 08:00, and the 07:30 is over 20 minutes old.
        assertTrue(stops.recentDepartures("F1", ServiceDay.WEEKDAY, 8 * 60).isEmpty())
        assertTrue(stops.recentDepartures("F1", ServiceDay.WEEKDAY, 9 * 60).isEmpty())

        val timetable = TimetableRepository(database)
        assertEquals(3, timetable.departures("F-1-2", ServiceDay.WEEKDAY, Direction.OUTBOUND).size)
        assertEquals(listOf(7 * 60 + 45), timetable.departures("F-1-2", ServiceDay.WEEKDAY, Direction.RETURN).map { it.minutesAfterMidnight })
        assertEquals(listOf("F1", "F2"), lines.routes("F-1-2").first { it.direction == Direction.OUTBOUND }.stops.map { it.code })
    }
}
