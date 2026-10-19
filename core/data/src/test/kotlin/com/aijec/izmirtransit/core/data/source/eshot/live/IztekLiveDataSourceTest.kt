package com.aijec.izmirtransit.core.data.source.eshot.live

import com.aijec.izmirtransit.core.data.failure
import com.aijec.izmirtransit.core.data.fixture
import com.aijec.izmirtransit.core.data.json
import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.live.RequestPacer
import com.aijec.izmirtransit.core.data.success
import com.aijec.izmirtransit.core.data.withIztekServer
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

// Fixtures are real responses captured on 20 Oct 2026 (research/fixtures).
class IztekLiveDataSourceTest {
    @Test
    fun `wrapped line positions - KoorX is latitude, comma decimals, Yon 0 is return`() =
        withIztekServer(fixture("night_hatotobuskonumlari_446.json")) { source, server ->
            val buses = source.lineBusPositions("446").success().value

            assertEquals("/api/iztek/hatotobuskonumlari/446", server.takeRequest().path)
            assertEquals(3, buses.size)
            val first = buses.first()
            assertEquals(2202, first.busId)
            assertEquals(Direction.RETURN, first.direction)
            assertEquals(38.453135, first.position.latitude, 1e-9)
            assertEquals(27.09729167, first.position.longitude, 1e-9)
        }

    @Test
    fun `Yon 1 is outbound`() = withIztekServer(fixture("night_hatotobuskonumlari_202.json")) { source, _ ->
        assertEquals(Direction.OUTBOUND, source.lineBusPositions("202").success().value.single().direction)
    }

    @Test
    fun `bare array of approaching buses`() =
        withIztekServer(fixture("night_duragayaklasanotobusler_10007.json")) { source, server ->
            val buses = source.approachingBuses("10007").success().value

            assertEquals("/api/iztek/duragayaklasanotobusler/10007", server.takeRequest().path)
            assertEquals(listOf("30", "29", "29"), buses.map { it.lineCode })
            val first = buses.first()
            assertEquals("KAYNAK - KONAK", first.lineName)
            assertEquals(Direction.OUTBOUND, first.direction) // HattinYonu uses dataset codes
            assertEquals(1, first.stopsRemaining)
            assertEquals(38.41557, first.position.latitude, 1e-9)
            assertEquals(false, first.wheelchairAccessible)
            assertEquals(true, buses[1].wheelchairAccessible)
        }

    @Test
    fun `line-and-stop endpoint uses both path segments`() =
        withIztekServer(fixture("night_hattinyaklasanotobusleri_446_21056.json")) { source, server ->
            assertEquals(emptyList(), source.lineApproachingBuses("446", "21056").success().value)
            assertEquals("/api/iztek/hattinyaklasanotobusleri/446/21056", server.takeRequest().path)
        }

    @Test
    fun `empty results are successes, not errors`() = withIztekServer(
        fixture("night_hatotobuskonumlari_99999.json"),
        fixture("night_duragayaklasanotobusler_21050.json"),
        MockResponse().setResponseCode(204),
        json(""),
    ) { source, _ ->
        assertEquals(emptyList(), source.lineBusPositions("99999").success().value)
        assertEquals(emptyList(), source.approachingBuses("21050").success().value)
        assertEquals(emptyList(), source.approachingBuses("21050").success().value)
        assertEquals(emptyList(), source.lineBusPositions("1").success().value)
    }

    @Test
    fun `malformed records are skipped one by one and numeric coordinates accepted`() = withIztekServer(
        json(
            """
            {"HataMesaj":"","HataVarMi":false,"HatOtobusKonumlari":[
              {"OtobusId":1,"Yon":1,"KoorX":"38,45","KoorY":"27,09"},
              {"OtobusId":2,"Yon":0,"KoorX":38.41,"KoorY":27.1},
              {"OtobusId":3,"Yon":1,"KoorX":"not a number","KoorY":"27,1"},
              {"OtobusId":4,"Yon":7,"KoorX":"38,4","KoorY":"27,1"},
              "not an object",
              {"OtobusId":5,"Yon":1,"KoorX":"38,4","KoorY":"27,1","Yeni":"unknown fields are ignored"}
            ]}
            """.trimIndent(),
        ),
    ) { source, _ ->
        val result = source.lineBusPositions("5").success()
        assertEquals(listOf(1, 2, 5), result.value.map { it.busId })
        assertEquals(38.41, result.value[1].position.latitude, 1e-9)
        assertEquals(3, result.skippedRecords)
    }

    @Test
    fun `HTTP failures map to typed, retryable-or-not errors`() = withIztekServer(
        json("{}", code = 500),
        json("{}", code = 404),
        json("<html>gateway</html>"),
        json("""{"HataMesaj":"Sunucu hatası","HataVarMi":true,"HatOtobusKonumlari":[]}"""),
        // Last: a 429 also makes the shared pacer hold back the next call for 5 s.
        json(com.aijec.izmirtransit.core.data.resource("fixtures/error_429_rate_limit.json"), code = 429),
    ) { source, _ ->
        source.lineBusPositions("5").failure().let {
            assertEquals(LiveError.SERVER, it.error)
            assertTrue(it.retryable)
        }
        assertEquals(false, source.lineBusPositions("5").failure().retryable)
        assertEquals(LiveError.MALFORMED, source.lineBusPositions("5").failure().error)
        source.lineBusPositions("5").failure().let {
            assertEquals(LiveError.SERVER, it.error)
            assertEquals("Sunucu hatası", it.detail)
        }
        source.lineBusPositions("5").failure().let {
            assertEquals(LiveError.RATE_LIMITED, it.error)
            assertTrue(it.retryable)
        }
    }

    @Test
    fun `unreachable server is a retryable network error`() {
        // Port 9 (discard) on localhost is closed on any normal machine.
        val api = IztekApi.create(IztekApi.httpClient("test"), "http://127.0.0.1:9/api/iztek/")
        val result = runBlocking { IztekLiveDataSource(api, RequestPacer(Duration.ZERO)).lineBusPositions("5") }
        val failure = result.failure()
        assertEquals(LiveError.NETWORK, failure.error)
        assertTrue(failure.retryable)
    }

    @Test
    fun `sends a User-Agent`() = withIztekServer(fixture("night_hatotobuskonumlari_5.json")) { source, server ->
        assertTrue(source.lineBusPositions("5") is FetchResult.Success)
        assertEquals("IzmirTransit-test", server.takeRequest().getHeader("User-Agent"))
    }
}
