package com.aijec.izmirtransit.core.data.trip

import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import kotlinx.coroutines.flow.StateFlow

/** What the traveller should do now, for the trip-tracking notification. */
sealed interface TripStatus {
    data class WalkTo(val stop: Stop, val meters: Int, val next: JourneyLeg.Ride?) : TripStatus
    data class WaitFor(val ride: JourneyLeg.Ride) : TripStatus
    data class OnBoard(val ride: JourneyLeg.Ride, val stopsLeft: Int, val nextStop: Stop) : TripStatus
    /** One stop to go: get ready. */
    data class GetOffNext(val ride: JourneyLeg.Ride) : TripStatus
    /** At the stop to get off at. */
    data class GetOffNow(val ride: JourneyLeg.Ride, val then: JourneyLeg.Ride?) : TripStatus
    data class WalkToDestination(val meters: Int) : TripStatus
    data object Arrived : TripStatus
}

/** A trip being followed, as the planner hands it to the tracking service. */
data class ActiveTrip(val journey: Journey, val destinationName: String)

/** Starts and stops trip tracking; implemented by the app as a foreground service. */
interface TripTracking {
    val active: StateFlow<ActiveTrip?>
    val status: StateFlow<TripStatus?>

    fun start(trip: ActiveTrip)

    fun stop()
}

/**
 * Follows a planned journey leg by leg from location fixes. Kept free of Android so it can
 * be tested; feed it every fix (or none) with the current minute on the journey's clock.
 *
 * A ride counts as boarded once the traveller moves along its route past the boarding stop;
 * stops left come from projecting the position onto the route. Underground (the metro) or
 * with a poor fix there is no usable position, so the ride is followed by the timetable.
 */
class TripProgress(private val journey: Journey) {
    private val legs = journey.legs
    private var index = 0
    private var boarded = false

    private class RideGeometry(val path: MeasuredPolyline, val stopsAlong: DoubleArray)

    private val geometry = HashMap<Int, RideGeometry>()

    private fun geometryOf(i: Int, ride: JourneyLeg.Ride): RideGeometry = geometry.getOrPut(i) {
        val path = MeasuredPolyline(ride.path.ifEmpty { ride.stops.map { it.location } })
        var from = 0.0
        val along = DoubleArray(ride.stops.size) { s ->
            val projected = path.projectAfter(ride.stops[s].location, from)?.distanceAlongMeters ?: from
            from = maxOf(from, projected)
            from
        }
        RideGeometry(path, along)
    }

    fun update(position: LatLng?, accuracyMeters: Float?, minute: Int): TripStatus {
        val fix = position?.takeIf { (accuracyMeters ?: 0f) <= MAX_ACCURACY_METERS }
        while (index < legs.size) {
            when (val leg = legs[index]) {
                is JourneyLeg.Walk -> {
                    val toStop = leg.toStop
                    if (toStop == null) {
                        // The last walk, to the destination.
                        val meters = fix?.let { distanceMeters(it, leg.to).toInt() } ?: return TripStatus.WalkToDestination(leg.meters)
                        return if (meters <= ARRIVED_METERS) TripStatus.Arrived.also { index = legs.size } else TripStatus.WalkToDestination(meters)
                    }
                    val next = legs.getOrNull(index + 1) as? JourneyLeg.Ride
                    val meters = fix?.let { distanceMeters(it, toStop.location).toInt() }
                    // At the stop, or already riding the next leg (the fix came late).
                    if (meters != null && meters <= AT_STOP_METERS) { index++; continue }
                    if (next != null && fix != null && movedAlong(index + 1, next, fix)) { index++; boarded = true; continue }
                    // No fix and the next ride is under way by the timetable: assume it was caught.
                    if (fix == null && next != null && minute > next.startMinute) { index++; continue }
                    return TripStatus.WalkTo(toStop, meters ?: leg.meters, next)
                }
                is JourneyLeg.Ride -> {
                    val g = geometryOf(index, leg)
                    val last = leg.stops.lastIndex
                    if (!boarded) {
                        boarded = if (fix != null) movedAlong(index, leg, fix) else minute > leg.startMinute
                        if (!boarded) return TripStatus.WaitFor(leg)
                    }
                    // Index of the last stop passed: by position, or by the timetable without one.
                    val passed = if (fix != null && (g.path.project(fix)?.distanceFromLineMeters ?: Double.MAX_VALUE) <= NEAR_ROUTE_METERS) {
                        val along = g.path.project(fix)!!.distanceAlongMeters
                        g.stopsAlong.indexOfLast { it <= along + AT_STOP_METERS }.coerceAtLeast(0)
                    } else {
                        val span = (leg.endMinute - leg.startMinute).coerceAtLeast(1)
                        (((minute - leg.startMinute).toDouble() / span) * last).toInt().coerceIn(0, last)
                    }
                    val atAlight = fix != null && distanceMeters(fix, leg.alight.location) <= AT_STOP_METERS * 2
                    val then = legs.drop(index + 1).firstOrNull { it is JourneyLeg.Ride } as? JourneyLeg.Ride
                    if (passed >= last || atAlight) {
                        // Off the vehicle: walked away from the stop, or well past it on foot.
                        val away = fix != null && distanceMeters(fix, leg.alight.location) > LEFT_STOP_METERS
                        val late = fix == null && minute > leg.endMinute + 1
                        if (away || late) { index++; boarded = false; continue }
                        return TripStatus.GetOffNow(leg, then)
                    }
                    val left = last - passed
                    return if (left <= 1) TripStatus.GetOffNext(leg) else TripStatus.OnBoard(leg, left, leg.stops[passed + 1])
                }
            }
        }
        return TripStatus.Arrived
    }

    /** On the route and past the boarding stop: aboard. */
    private fun movedAlong(i: Int, ride: JourneyLeg.Ride, fix: LatLng): Boolean {
        val g = geometryOf(i, ride)
        val projection = g.path.project(fix) ?: return false
        return projection.distanceFromLineMeters <= NEAR_ROUTE_METERS && projection.distanceAlongMeters > g.stopsAlong[0] + BOARDED_AFTER_METERS
    }

    companion object {
        const val MAX_ACCURACY_METERS = 150f
        const val AT_STOP_METERS = 60
        const val NEAR_ROUTE_METERS = 100.0
        const val BOARDED_AFTER_METERS = 200.0
        const val LEFT_STOP_METERS = 150
        const val ARRIVED_METERS = 50
    }
}
