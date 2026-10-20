package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BusMotionTest {
    // A straight 5 km east-west route.
    private val route = MeasuredPolyline(listOf(LatLng(38.4, 27.1), LatLng(38.4, 27.1575)))
    private val routes = mapOf(Direction.OUTBOUND to route)
    private val motion = BusMotion(speedFactor = 1.0)

    private fun at(meters: Double) = route.pointAt(meters)
    private fun bus(meters: Double, id: Int = 1) = LiveBus(id, "5", Direction.OUTBOUND, at(meters))
    private fun drawnAlong(nowMillis: Long, id: Int = 1): Double =
        assertNotNull(route.project(assertNotNull(motion.positionsAt(nowMillis)[id]).first)).distanceAlongMeters

    @Test
    fun `a new bus is drawn where reported and does not move without a speed`() {
        motion.update(listOf(bus(1000.0)), routes, nowMillis = 0)
        assertEquals(1000.0, drawnAlong(0), 1.0)
        assertEquals(1000.0, drawnAlong(30_000), 1.0)
    }

    @Test
    fun `after two reports it keeps moving at the measured speed`() {
        motion.update(listOf(bus(1000.0)), routes, 0)
        motion.update(listOf(bus(1150.0)), routes, 15_000) // 10 m/s
        // After the correction glide, 5 s later it should be ~50 m past the report.
        assertEquals(1200.0, drawnAlong(20_000), 2.0)
    }

    @Test
    fun `an unchanged report keeps extrapolating from the last real update`() {
        motion.update(listOf(bus(1000.0)), routes, 0)
        motion.update(listOf(bus(1150.0)), routes, 15_000)
        motion.update(listOf(bus(1150.0)), routes, 30_000) // server hasn't updated
        assertEquals(1350.0, drawnAlong(35_000), 2.0)
    }

    @Test
    fun `extrapolation is capped`() {
        motion.update(listOf(bus(1000.0)), routes, 0)
        motion.update(listOf(bus(1150.0)), routes, 15_000)
        // Cap is 45 s at 10 m/s = 450 m past the last report, however long we wait.
        assertEquals(1600.0, drawnAlong(15_000 + 600_000), 2.0)
    }

    @Test
    fun `a correction glides instead of snapping`() {
        motion.update(listOf(bus(1000.0)), routes, 0)
        motion.update(listOf(bus(1150.0)), routes, 15_000)
        val before = drawnAlong(30_000) // predicted ~1300
        motion.update(listOf(bus(1250.0)), routes, 30_000) // real position is behind the prediction
        val justAfter = drawnAlong(30_100)
        assertTrue(kotlin.math.abs(justAfter - before) < 40, "glide starts from the drawn position: $before -> $justAfter")
        assertEquals(1250.0 + 7.5 * 2.0, drawnAlong(32_000), 3.0) // settled, moving at the blended 7.5 m/s
    }

    @Test
    fun `backwards jumps stop prediction`() {
        motion.update(listOf(bus(2000.0)), routes, 0)
        motion.update(listOf(bus(1500.0)), routes, 15_000)
        assertEquals(1500.0, drawnAlong(40_000), 2.0)
    }

    @Test
    fun `off-route buses are drawn as reported and vanished buses are dropped`() {
        val offRoute = LiveBus(2, "5", Direction.OUTBOUND, LatLng(38.41, 27.12)) // ~1.1 km north
        motion.update(listOf(bus(1000.0), offRoute), routes, 0)
        assertEquals(offRoute.position, motion.positionsAt(5_000)[2]?.first)
        motion.update(listOf(bus(1000.0)), routes, 15_000)
        assertEquals(setOf(1), motion.positionsAt(15_000).keys)
    }
}
