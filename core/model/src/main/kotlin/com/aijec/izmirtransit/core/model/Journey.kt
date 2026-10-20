package com.aijec.izmirtransit.core.model

import com.aijec.izmirtransit.core.model.geo.LatLng

/**
 * A planned trip from a point to a point: walks and rides. Times are minutes on the
 * service day's clock (may pass 1440 after midnight), like every timetable in the app.
 */
data class Journey(
    val departureMinute: Int,
    val arrivalMinute: Int,
    val legs: List<JourneyLeg>,
) {
    val durationMinutes: Int get() = arrivalMinute - departureMinute
    val rides: List<JourneyLeg.Ride> get() = legs.filterIsInstance<JourneyLeg.Ride>()
    val walkingMeters: Int get() = legs.filterIsInstance<JourneyLeg.Walk>().sumOf { it.meters }
}

sealed interface JourneyLeg {
    val startMinute: Int
    val endMinute: Int

    data class Walk(
        val from: LatLng,
        val to: LatLng,
        /** Null for the trip's own start or end point. */
        val fromStop: Stop?,
        val toStop: Stop?,
        val meters: Int,
        override val startMinute: Int,
        override val endMinute: Int,
    ) : JourneyLeg

    data class Ride(
        val line: Line,
        val direction: Direction,
        val board: Stop,
        val alight: Stop,
        /** Every stop from [board] to [alight], both included. */
        val stops: List<Stop>,
        /** The route between the two stops, for the map. */
        val path: List<LatLng>,
        /** Where the vehicle is heading (its final stop). */
        val headsign: String,
        override val startMinute: Int,
        override val endMinute: Int,
        /**
         * True when the times come from a stop-by-stop timetable (rail, ferry). Bus times
         * along the route are estimated from the departure at the first stop.
         */
        val exactTimes: Boolean,
    ) : JourneyLeg
}
