package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.data.fixture
import com.aijec.izmirtransit.core.data.resource
import com.aijec.izmirtransit.core.data.source.ImportIssues
import com.aijec.izmirtransit.core.data.source.eshot.EshotCsv
import com.aijec.izmirtransit.core.data.source.eshot.live.RawBusPosition
import com.aijec.izmirtransit.core.data.success
import com.aijec.izmirtransit.core.data.withIztekServer
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.test.Test
import kotlin.test.assertEquals

class GhostFilterTest {
    private val filter = GhostFilter()

    private fun realRoutes(line: String): Map<Direction, MeasuredPolyline> =
        EshotCsv.parseShapes(resource("shapes/shape_$line.csv").reader(), ImportIssues())
            .mapKeys { it.key.second }
            .mapValues { MeasuredPolyline(it.value) }

    private fun realPositions(fixtureName: String, line: String): List<RawBusPosition> =
        withIztekServer(fixture(fixtureName)) { source, _ -> source.lineBusPositions(line).success().value }

    @Test
    fun `line 470 - eleven records, one real bus`() {
        // Captured 20 Oct 2026 00:30: buses 11246 (incl. a 0,0 copy) and 11815 were all frozen
        // far off the route; only one copy of 2063 was on it.
        val raw = realPositions("night_hatotobuskonumlari_470_with_ghosts.json", "470")
        val result = filter.filter("470", raw, realRoutes("470"))

        assertEquals(11, raw.size)
        assertEquals(listOf(2063), result.buses.map { it.busId })
        assertEquals(LatLng(38.42223333, 27.13742167), result.buses.single().position)
        assertEquals(10, result.dropped)
    }

    @Test
    fun `line 671 - keeps the on-route copy of each bus`() {
        val raw = realPositions("night_hatotobuskonumlari_671_with_ghosts.json", "671")
        val result = filter.filter("671", raw, realRoutes("671"))

        assertEquals(
            mapOf(2065 to LatLng(38.38224333, 27.12143667), 3105 to LatLng(38.37758833, 27.10410333)),
            result.buses.associate { it.busId to it.position },
        )
    }

    @Test
    fun `without shapes, prefers the copy that moved since the last poll`() {
        val frozen = RawBusPosition(7, Direction.OUTBOUND, LatLng(38.40, 27.10))
        val before = RawBusPosition(7, Direction.OUTBOUND, LatLng(38.41, 27.12))
        val after = RawBusPosition(7, Direction.OUTBOUND, LatLng(38.412, 27.122))

        val result = filter.filter("1", listOf(frozen, after), routes = emptyMap(), previousRaw = listOf(frozen, before))
        assertEquals(after.position, result.buses.single().position)
    }

    @Test
    fun `when nothing moved, stays with last poll's choice`() {
        val a = RawBusPosition(7, Direction.OUTBOUND, LatLng(38.40, 27.10))
        val b = RawBusPosition(7, Direction.OUTBOUND, LatLng(38.41, 27.12))

        val result = filter.filter(
            "1", listOf(a, b), routes = emptyMap(),
            previousRaw = listOf(a, b), previousSelected = mapOf(7 to b.position),
        )
        assertEquals(b.position, result.buses.single().position)
    }

    @Test
    fun `single-record plausibility check for the stop endpoints`() {
        val routes = realRoutes("470")
        assertEquals(true, filter.isPlausible(LatLng(38.42223333, 27.13742167), routes))
        assertEquals(false, filter.isPlausible(LatLng(38.43759333, 27.16762), routes)) // frozen 11815 copy
        assertEquals(false, filter.isPlausible(LatLng(0.0, 0.0), emptyMap()))
        assertEquals(true, filter.isPlausible(LatLng(38.4, 27.1), emptyMap()))
    }

    @Test
    fun `zero and out-of-region coordinates are always dropped`() {
        val raw = listOf(
            RawBusPosition(1, Direction.OUTBOUND, LatLng(0.0, 0.0)),
            RawBusPosition(2, Direction.OUTBOUND, LatLng(27.1, 38.4)), // swapped
            RawBusPosition(3, Direction.RETURN, LatLng(38.4, 27.1)),
        )
        assertEquals(listOf(3), filter.filter("1", raw, emptyMap()).buses.map { it.busId })
    }
}
