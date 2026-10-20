package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.data.fixture
import com.aijec.izmirtransit.core.data.resource
import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.eshot.EshotCsv
import com.aijec.izmirtransit.core.data.source.eshot.live.RawApproachingBus
import com.aijec.izmirtransit.core.data.source.eshot.live.RawBusPosition
import com.aijec.izmirtransit.core.data.success
import com.aijec.izmirtransit.core.data.withIztekServer
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real responses captured on Tue 20 Oct 2026, 10:26–11:13 Istanbul, under daytime load. */
class DayFixturesTest {
    private val filter = GhostFilter()
    private val estimator = EtaEstimator()

    private fun realRoutes(line: String): Map<Direction, MeasuredPolyline> =
        EshotCsv.parseShapes(resource("shapes/shape_$line.csv").reader(), ImportIssues())
            .mapKeys { it.key.second }
            .mapValues { MeasuredPolyline(it.value) }

    private fun positions(fixtureName: String, line: String): List<RawBusPosition> =
        withIztekServer(fixture(fixtureName)) { source, _ -> source.lineBusPositions(line).success().value }

    private fun approaching(fixtureName: String, stop: String): List<RawApproachingBus> =
        withIztekServer(fixture(fixtureName)) { source, _ -> source.approachingBuses(stop).success().value }

    private fun Map<Direction, MeasuredPolyline>.distanceFrom(position: LatLng): Double =
        values.minOf { it.project(position)?.distanceFromLineMeters ?: Double.MAX_VALUE }

    @Test
    fun `line 121 - nine records, five real buses, every kept bus on its shape`() {
        val routes = realRoutes("121")
        val raw = positions("day_hatotobuskonumlari_121_poll1.json", "121")
        val result = filter.filter("121", raw, routes)

        assertEquals(9, raw.size)
        assertEquals(listOf(2041, 2250, 3131, 11780, 12193), result.buses.map { it.busId })
        assertEquals(4, result.dropped)
        result.buses.forEach { assertTrue(routes.distanceFrom(it.position) < 50.0, "bus ${it.busId} off route") }
    }

    @Test
    fun `line 121 - successive polls follow the moving copy, never the frozen one`() {
        val routes = realRoutes("121")
        val polls = (1..3).map { positions("day_hatotobuskonumlari_121_poll$it.json", "121") }
        var previousRaw = emptyList<RawBusPosition>()
        var previousSelected = emptyMap<Int, LatLng>()
        val chosen = polls.map { raw ->
            filter.filter("121", raw, routes, previousRaw, previousSelected).also {
                previousRaw = raw
                previousSelected = it.buses.associate { bus -> bus.busId to bus.position }
            }
        }

        // 11780 has a copy frozen 559 m off the route at the same point in all three polls;
        // the real one moved ~1 km along the shape.
        val frozen = LatLng(38.48458667, 27.07107333)
        val path = chosen.map { result -> result.buses.single { it.busId == 11780 }.position }
        assertTrue(frozen !in path)
        assertEquals(3, path.toSet().size)
        chosen.forEach { assertEquals(5, it.buses.size) }
    }

    @Test
    fun `line 879 - twenty-seven records for fourteen buses under load`() {
        val routes = realRoutes("879")
        val raw = positions("day_hatotobuskonumlari_879.json", "879")
        val result = filter.filter("879", raw, routes)

        assertEquals(27, raw.size)
        assertEquals(14, raw.map { it.busId }.toSet().size)
        // 11811 and 12106 only had copies 0.8 km+ off the route (a depot cluster) or at 0,0.
        assertEquals(
            listOf(1731, 1738, 1741, 2104, 2235, 3014, 11525, 11630, 11665, 11722, 11834, 12478),
            result.buses.map { it.busId },
        )
        result.buses.forEach { assertTrue(routes.distanceFrom(it.position) < 100.0) }
    }

    @Test
    fun `line 30 - two of four buses exist only as ghosts`() {
        val result = filter.filter("30", positions("day_hatotobuskonumlari_30.json", "30"), realRoutes("30"))
        assertEquals(listOf(11296, 12436), result.buses.map { it.busId })
        assertEquals(8, result.dropped)
    }

    @Test
    fun `line 5 - drops zero copies, the depot cluster and a bus 15 km away`() {
        val result = filter.filter("5", positions("day_hatotobuskonumlari_5.json", "5"), realRoutes("5"))
        assertEquals(listOf(11445, 11694, 12502), result.buses.map { it.busId })
    }

    @Test
    fun `stop 10007 - the line 30 ghost listed 9 stops away is implausible`() {
        val routes = realRoutes("30")
        val line30 = approaching("day_duragayaklasanotobusler_10007.json", "10007").filter { it.lineCode == "30" }
        assertEquals(
            mapOf(12436 to true, 12383 to false),
            line30.associate { it.busId to filter.isPlausible(it.position, routes) },
        )
    }

    @Test
    fun `stop 40001 - sixteen approaching buses, all estimated from the route and plausible`() {
        val stop = LatLng(38.3933, 27.1434)
        val buses = approaching("day_duragayaklasanotobusler_40001.json", "40001")
        assertEquals(16, buses.size)

        for (line in listOf("470", "671")) {
            val routes = realRoutes(line)
            buses.filter { it.lineCode == line }.forEach { bus ->
                assertTrue(filter.isPlausible(bus.position, routes))
                val eta = estimator.estimate(bus.position, bus.stopsRemaining, stop, routes[bus.direction])
                assertEquals(EtaBasis.ROUTE_DISTANCE, eta.basis, "bus ${bus.busId}")
                // 10–30 stops away and 5–17 km out: tens of minutes, not hours or zero.
                assertTrue(eta.minutes in 10..60, "bus ${bus.busId}: ${eta.minutes} min")
            }
        }
    }

    @Test
    fun `estimates match observed arrivals within the measured error`() {
        // Bus 12436 (line 30) was 2.0 km before stop 10007 and left the stop's list 5.3 min later.
        val line30 = approaching("day_duragayaklasanotobusler_10007.json", "10007").single { it.busId == 12436 }
        val eta30 = estimator.estimate(
            line30.position, line30.stopsRemaining, LatLng(38.415144105211, 27.1277200912719),
            realRoutes("30")[line30.direction],
        )
        assertEquals(EtaBasis.ROUTE_DISTANCE, eta30.basis)
        assertTrue(abs(eta30.minutes - 5.3) <= MAX_ERROR_MINUTES, "line 30: ${eta30.minutes} min")

        // Bus 2041 (line 121) was 1.3 km before stop 10107 and crossed it 3.9 min later.
        val line121 = approaching("day_duragayaklasanotobusler_10107.json", "10107").single()
        val eta121 = estimator.estimate(
            line121.position, line121.stopsRemaining, LatLng(38.4319, 27.1375), realRoutes("121")[line121.direction],
        )
        assertEquals(EtaBasis.ROUTE_DISTANCE, eta121.basis)
        assertTrue(abs(eta121.minutes - 3.9) <= MAX_ERROR_MINUTES, "line 121: ${eta121.minutes} min")
    }

    private companion object {
        /** 63% of 33k observed daytime trips were within 2 min at 300 m/min. */
        const val MAX_ERROR_MINUTES = 2.0
    }
}
