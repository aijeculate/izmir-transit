package com.aijec.izmirtransit.core.data.routing

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneyPlannerTest {
    // A bus runs east A → B → C (about 1.7 km, so 6 minutes at 300 m/min). Next to C is the
    // metro's C2, from which trains run 3.3 km north to D.
    private val a = Stop(TransitMode.BUS, "1", "A", LatLng(38.40, 27.10))
    private val b = Stop(TransitMode.BUS, "2", "B", LatLng(38.40, 27.11))
    private val c = Stop(TransitMode.BUS, "3", "C", LatLng(38.40, 27.12))
    private val c2 = Stop(TransitMode.METRO, "M1", "C Metro", LatLng(38.4003, 27.1203))
    private val m = Stop(TransitMode.METRO, "M2", "Mid", LatLng(38.415, 27.12))
    private val d = Stop(TransitMode.METRO, "M3", "D", LatLng(38.43, 27.12))

    private fun line(code: String, mode: TransitMode) =
        Line(mode, code, name = code, routeDescription = "", notes = "", origin = "", destination = "")

    private val bus = PatternInput(
        line = line("5", TransitMode.BUS),
        direction = Direction.OUTBOUND,
        stopCodes = listOf("1", "2", "3"),
        shape = listOf(a.location, c.location),
        originDepartures = listOf(600, 620),
    )
    private val metro = PatternInput(
        line = line("M", TransitMode.METRO),
        direction = Direction.OUTBOUND,
        stopCodes = listOf("M1", "M2", "M3"),
        shape = emptyList(),
        stopDepartures = mapOf(
            // The 06:08 only runs to Mid; the 06:10 goes all the way.
            "M1" to listOf(608 to "Mid", 610 to "D", 630 to "D"),
            "M2" to listOf(611 to "Mid", 613 to "D", 633 to "D"),
        ),
    )
    private val network = TransitNetwork.build(listOf(a, b, c, c2, m, d), listOf(bus, metro))
    private val planner = JourneyPlanner(network)

    @Test
    fun `bus, short walk, then metro — skipping a train that ends early`() {
        val journeys = planner.plan(origin = LatLng(38.4, 27.0995), destination = LatLng(38.4303, 27.1201), startMinute = 595)
        val best = journeys.first()
        val rides = best.rides
        assertEquals(listOf("5", "M"), rides.map { it.line.code })
        assertEquals("A", rides[0].board.name)
        assertEquals("C", rides[0].alight.name)
        assertEquals(600, rides[0].startMinute)
        assertEquals(606, rides[0].endMinute)
        // 06:06 at C, a minute's walk and a minute to change: the 06:08 would fit but stops at Mid.
        assertEquals(610, rides[1].startMinute)
        assertEquals("D", rides[1].headsign)
        assertEquals(listOf("C Metro", "Mid", "D"), rides[1].stops.map { it.name })
        assertTrue(rides[1].exactTimes)
        // Walk to the bus, between the two, and from the last stop.
        assertEquals(3, best.legs.count { it is JourneyLeg.Walk })
        // Leaves just in time for the bus rather than at the requested 05:55.
        assertEquals(599, best.departureMinute)
    }

    @Test
    fun `a short trip can be walked, and the bus wins when it is quicker`() {
        val journeys = planner.plan(origin = a.location, destination = b.location, startMinute = 595)
        // Walking 870 m takes 15 minutes; the 06:00 bus gets there at 06:03.
        assertEquals(listOf("5"), journeys.first().rides.map { it.line.code })
        assertTrue(journeys.any { j -> j.legs.size == 1 && j.legs.single() is JourneyLeg.Walk })
    }

    @Test
    fun `nothing after the last departure`() {
        val journeys = planner.plan(origin = LatLng(38.4, 27.0995), destination = LatLng(38.4303, 27.1201), startMinute = 700)
        assertTrue(journeys.none { it.rides.isNotEmpty() })
    }
}
