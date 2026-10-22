package com.aijec.izmirtransit.core.data.trip

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TripProgressTest {
    // Walk to A, ride a bus east through B, C, D (each ~870 m apart), walk to the destination.
    private val stops = (0..3).map { Stop(TransitMode.BUS, "${it + 1}", "S${it + 1}", LatLng(38.40, 27.10 + it * 0.01)) }
    private val line = Line(TransitMode.BUS, "5", name = "5", routeDescription = "", notes = "", origin = "", destination = "S4")
    private val start = LatLng(38.401, 27.099)
    private val end = LatLng(38.401, 27.131)
    private val ride = JourneyLeg.Ride(line, Direction.OUTBOUND, stops.first(), stops.last(), stops, stops.map { it.location }, "S4", 600, 612, exactTimes = false)
    private val journey = Journey(
        595,
        615,
        listOf(
            JourneyLeg.Walk(start, stops[0].location, null, stops[0], 150, 595, 600),
            ride,
            JourneyLeg.Walk(stops[3].location, end, stops[3], null, 120, 612, 615),
        ),
    )

    @Test
    fun `walks, waits, rides counting stops, gets off and arrives`() {
        val trip = TripProgress(journey)
        assertIs<TripStatus.WalkTo>(trip.update(start, 10f, 596))
        assertIs<TripStatus.WaitFor>(trip.update(stops[0].location, 10f, 599))
        // Between S1 and S2 on the road: aboard, three stops left (S2, S3, S4).
        val riding = trip.update(LatLng(38.40, 27.105), 15f, 602)
        assertIs<TripStatus.OnBoard>(riding)
        assertEquals(3, riding.stopsLeft)
        assertEquals("S2", riding.nextStop.name)
        // Past S3: next stop is the one to get off at.
        assertIs<TripStatus.GetOffNext>(trip.update(LatLng(38.40, 27.123), 15f, 609))
        assertIs<TripStatus.GetOffNow>(trip.update(stops[3].location, 15f, 612))
        // Walked away from the stop: on to the destination.
        val walking = trip.update(LatLng(38.4005, 27.1325), 10f, 613)
        assertIs<TripStatus.WalkToDestination>(walking)
        assertEquals(TripStatus.Arrived, trip.update(end, 10f, 615))
    }

    @Test
    fun `without a usable fix the timetable carries the ride`() {
        val trip = TripProgress(journey)
        trip.update(stops[0].location, 10f, 599)
        // Underground: no fix, 6 of 12 minutes in, halfway along three hops.
        val riding = trip.update(null, null, 606)
        assertIs<TripStatus.OnBoard>(riding)
        assertEquals(2, riding.stopsLeft)
        // A fix too vague to trust is ignored the same way.
        assertIs<TripStatus.GetOffNext>(trip.update(LatLng(38.5, 27.5), 900f, 610))
        assertIs<TripStatus.WalkToDestination>(trip.update(null, null, 614))
    }
}
