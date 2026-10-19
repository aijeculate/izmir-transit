package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.database.JvmDatabases
import com.aijec.izmirtransit.core.model.ServiceDay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StaticDataRefreshTest {
    private val files = mutableMapOf(
        "eshot-otobus-duraklari.csv" to "DURAK_ID;DURAK_ADI;ENLEM;BOYLAM;DURAKTAN_GECEN_HATLAR\r\n100;Narlıdere;38.4001;27.10;5\r\n200;Şirinyer;38.4001;27.115;5\r\n",
        "eshot-otobus-hatlari.csv" to "HAT_NO;HAT_ADI;GUZERGAH_ACIKLAMA;ACIKLAMA;HAT_BASLANGIC;HAT_BITIS\r\n5;NARLIDERE - ÜÇKUYULAR İSKELE;;;NARLIDERE;ÜÇKUYULAR İSKELE\r\n",
        "eshot-otobus-hat-guzergahlari.csv" to "HAT_NO;YON;BOYLAM;ENLEM\r\n5;1;27.10;38.40\r\n5;1;27.13;38.40\r\n",
        "eshot-otobus-hareketsaatleri.csv" to "HAT_NO;TARIFE_ID;GIDIS_SAATI;DONUS_SAATI;SIRA\r\n5;1;06:00;06:45;1\r\n",
        "eshot-otobus-hat-duyurulari.csv" to "HAT_NO;BASLIK;BASLAMA_TARIHI;BITIS_TARIHI\r\n",
        "eshot-otobus-baglantili-hatlar.csv" to "BAGLANTI_TIP_ID;HAT_NO;GIDIS_CALISMA_SAATI;DONUS_CALISMA_SAATI\r\n",
    )
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.path.orEmpty().substringAfterLast('/')
                return files[name]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        start()
    }
    private val database = JvmDatabases.inMemory()
    private val workDir = Files.createTempDirectory("refresh").toFile()
    private val clock = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC)
    private val tiny = StaticDataRefresher.MinimumCounts(lines = 1, stops = 1, shapes = 1, departures = 1)

    private fun refresher(minimum: StaticDataRefresher.MinimumCounts = tiny) = StaticDataRefresher(
        EshotDownloader(OkHttpClient(), csvBaseUrl = server.url("/docs/").toString(), gtfsUrl = server.url("/gtfs/feed.zip").toString()),
        database, workDir, clock, minimum,
    )

    @AfterTest
    fun tearDown() {
        server.shutdown()
        database.close()
        workDir.deleteRecursively()
    }

    @Test
    fun `downloads, imports and cleans up, tolerating a missing GTFS feed`() = runBlocking {
        val outcome = refresher().refresh()

        assertIs<StaticDataRefresher.Outcome.Updated>(outcome)
        assertEquals("NARLIDERE - ÜÇKUYULAR İSKELE", LineRepository(database).line("5")?.name)
        assertEquals(1, TimetableRepository(database).departures("5", ServiceDay.WEEKDAY, com.aijec.izmirtransit.core.model.Direction.OUTBOUND).size)
        assertTrue(workDir.listFiles().orEmpty().isEmpty(), "download directory removed")
    }

    @Test
    fun `suspiciously small data is rejected and the old data kept`() = runBlocking {
        refresher().refresh()
        files["eshot-otobus-hatlari.csv"] = "HAT_NO;HAT_ADI\r\n" // truncated upstream

        val outcome = refresher(StaticDataRefresher.MinimumCounts(lines = 1, stops = 1, shapes = 0, departures = 0)).refresh()

        assertIs<StaticDataRefresher.Outcome.Rejected>(outcome)
        assertEquals("NARLIDERE - ÜÇKUYULAR İSKELE", LineRepository(database).line("5")?.name)
    }

    @Test
    fun `a missing CSV fails the refresh so it can be retried`() {
        files.remove("eshot-otobus-hatlari.csv")
        assertFailsWith<IOException> { runBlocking { refresher().refresh() } }
    }

    @Test
    fun `service moment rolls over at 04_00 and clock formatting wraps`() {
        // Tuesday 00:30 → Monday's (weekday) timetable, minute 1470.
        assertEquals(
            TimetableRepository.ServiceMoment(ServiceDay.WEEKDAY, 24 * 60 + 30),
            TimetableRepository.serviceMoment(LocalDateTime.of(2026, 10, 20, 0, 30)),
        )
        // Sunday 01:00 → Saturday's timetable.
        assertEquals(ServiceDay.SATURDAY, TimetableRepository.serviceMoment(LocalDateTime.of(2026, 10, 25, 1, 0)).serviceDay)
        assertEquals(ServiceDay.SUNDAY, TimetableRepository.serviceMoment(LocalDateTime.of(2026, 10, 25, 4, 0)).serviceDay)
        assertEquals("00:05", TimetableRepository.formatClock(24 * 60 + 5))
        assertEquals("23:40", TimetableRepository.formatClock(23 * 60 + 40))
    }
}
