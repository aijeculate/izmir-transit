package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.ServiceDay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IzbanImporterTest {
    private val dir = Files.createTempDirectory("izban-test").toFile()
    private val database = JvmDatabases.inMemory()

    @AfterTest
    fun tearDown() {
        database.close()
        dir.deleteRecursively()
    }

    // Trimmed from a real izban.com.tr response (Halkapınar → Alsancak, Monday).
    private val resultPage = """
        <select name="ctl00${'$'}CPH${'$'}drpHareketDur" id="ctl00_CPH_drpHareketDur" class="hareketDrp">
            <option selected="selected" value="38">Aliağa</option>
            <option value="37">Bi&#231;erova</option>
            <option value="19">Halkapınar</option>
        </select>
        <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="abc+/=" />
        <tr><th style='text-align:center;'>Hareket Saati</th><th style='text-align:center;'>Varış Saati</th></tr>
        <tr class='normal-row'><td style='text-align:center;border:none !important;'>00:05</td><td style='text-align:center;border:none !important;'>00:12</td></tr>
        <tr class='alt-row'><td style='text-align:center;border:none !important;'>05:40</td><td style='text-align:center;border:none !important;'>05:47</td></tr>
    """.trimIndent()

    @Test
    fun `parses the station dropdown, result table and form state`() {
        assertEquals(listOf(38 to "Aliağa", 37 to "Biçerova", 19 to "Halkapınar"), IzbanImporter.parseStations(resultPage))
        assertEquals(listOf("00:05" to "00:12", "05:40" to "05:47"), IzbanImporter.parseLegTimes(resultPage))
        assertEquals("abc+/=", IzbanImporter.hiddenFields(resultPage)["__VIEWSTATE"])
        // The empty form (before a query) has no result table.
        assertNull(IzbanImporter.parseLegTimes("<select></select>"))
    }

    @Test
    fun `legs chain into trips, with short runs ending where they stop`() {
        val trips = IzbanImporter.chainTrips(
            listOf(
                listOf(600 to 605, 620 to 625), // A → B
                listOf(606 to 610), // B → C: only the 10:00 train goes on (dwell 1 min)
            ),
        )
        assertEquals(
            setOf(
                listOf(IzbanImporter.Call(0, 600), IzbanImporter.Call(1, 606), IzbanImporter.Call(2, 610)),
                listOf(IzbanImporter.Call(0, 620), IzbanImporter.Call(1, 625)),
            ),
            trips.toSet(),
        )
    }

    @Test
    fun `builds stations, boards and headsigns from saved legs`() = runBlocking {
        File(dir, IzbanImporter.STATIONS_FILE).writeText("38\tAliağa\n37\tBiçerova\n8\tAdnan Menderes Havalimanı")
        fun leg(day: Int, from: Int, to: Int, vararg rows: String) = File(dir, IzbanImporter.legFile(day, from, to)).writeText(rows.joinToString("\n"))
        // Weekday: one train Aliağa → Havalimanı, one short run Aliağa → Biçerova, one northbound after midnight.
        leg(1, 38, 37, "06:00\t06:05", "06:30\t06:35")
        leg(1, 37, 8, "06:06\t06:40")
        leg(1, 8, 37, "00:10\t00:45")
        leg(1, 37, 38, "00:46\t00:50")
        for (day in listOf(6, 7)) for ((a, b) in listOf(38 to 37, 37 to 8, 8 to 37, 37 to 38)) leg(day, a, b)

        val zip = File(dir, "izban.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("stops.txt"))
            out.write("stop_id,stop_name,stop_lon,stop_lat\n1,Aliağa,27.0,38.8\n2,Biçerova,27.0,38.7\n3,Adnan Menderes,27.1,38.3\n".toByteArray())
            out.closeEntry()
        }

        val result = IzbanImporter.build(dir, zip)
        database.staticDataDao().replaceMode(result.data)
        val stops = StopRepository(database)

        // "Adnan Menderes Havalimanı" on the website matches "Adnan Menderes" in the feed.
        assertEquals(listOf("I38", "I37", "I8"), result.data.stops.map { it.code })
        val line = LineRepository(database).line(RailImporter.IZBAN_LINE)!!
        assertEquals("Aliağa – Adnan Menderes Havalimanı", line.name)

        val aliaga = stops.scheduledDepartures("I38", ServiceDay.WEEKDAY, 0)
        assertEquals(listOf(360 to "Adnan Menderes Havalimanı", 390 to "Biçerova"), aliaga.map { it.minutesAfterMidnight to it.headsign })
        // The 00:10 belongs to the evening before, like every after-midnight time in the app.
        val bicerova = stops.scheduledDepartures("I37", ServiceDay.WEEKDAY, 0)
        assertEquals(listOf(366 to Direction.OUTBOUND, 1486 to Direction.RETURN), bicerova.map { it.minutesAfterMidnight to it.direction })
        assertEquals("Aliağa", bicerova.last().headsign)
    }
}
