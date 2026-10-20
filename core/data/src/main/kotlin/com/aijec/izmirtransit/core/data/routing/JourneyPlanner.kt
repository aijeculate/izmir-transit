package com.aijec.izmirtransit.core.data.routing

import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.distanceMeters

/**
 * Earliest-arrival trip planning with RAPTOR (Delling et al., "Round-Based Public Transit
 * Routing"): round k finds the best arrival at every stop using k rides, then lets people
 * walk to nearby stops. Each round's best arrival at the destination is one answer, so the
 * result offers the fastest trip for each number of changes.
 */
class JourneyPlanner(private val network: TransitNetwork) {

    fun plan(
        origin: LatLng,
        destination: LatLng,
        startMinute: Int,
        maxRides: Int = MAX_RIDES,
    ): List<Journey> {
        val stopCount = network.stops.size
        val rounds = maxRides + 1
        // arrival[k][s]: best arrival at s with exactly-at-most k rides; how it got there.
        val rideArrival = Array(rounds) { IntArray(stopCount) { NONE } }
        val rideFrom = Array(rounds) { arrayOfNulls<RideLabel>(stopCount) }
        val walkArrival = Array(rounds) { IntArray(stopCount) { NONE } }
        val walkFrom = Array(rounds) { IntArray(stopCount) { -1 } }
        val best = IntArray(stopCount) { NONE }
        fun arrival(k: Int, s: Int) = minOf(rideArrival[k][s], walkArrival[k][s])

        // Round 0: walk from the origin to stops around it.
        val access = nearbyOrWider(origin)
        val marked = BooleanArray(stopCount)
        for ((s, meters) in access) {
            val t = startMinute + TransitNetwork.walkMinutes(meters)
            if (t < walkArrival[0][s]) {
                walkArrival[0][s] = t
                walkFrom[0][s] = ORIGIN
                best[s] = minOf(best[s], t)
                marked[s] = true
            }
        }
        val egress = nearbyOrWider(destination).associate { (s, meters) -> s to TransitNetwork.walkMinutes(meters) }
        var bestAtTarget = NONE

        for (k in 1 until rounds) {
            // Patterns to scan, from the earliest marked stop along each.
            val scanFrom = HashMap<Int, Int>()
            for (s in 0 until stopCount) {
                if (!marked[s]) continue
                for (entry in network.patternsAt[s]) {
                    val p = entry / TransitNetwork.POSITION_FACTOR
                    val position = entry % TransitNetwork.POSITION_FACTOR
                    scanFrom[p] = minOf(scanFrom[p] ?: Int.MAX_VALUE, position)
                }
            }
            marked.fill(false)
            val improved = ArrayList<Int>()

            for ((p, first) in scanFrom) {
                val pattern = network.patterns[p]
                var boardPosition = -1
                var boardMinute = 0
                var reach = -1
                var headsign = ""
                for (position in first until pattern.stops.size) {
                    val s = pattern.stops[position]
                    // Ride on to this stop.
                    if (boardPosition >= 0 && position <= reach) {
                        val t = boardMinute + pattern.offsets[position] - pattern.offsets[boardPosition]
                        if (t < best[s] && t < bestAtTarget) {
                            rideArrival[k][s] = t
                            rideFrom[k][s] = RideLabel(p, boardPosition, position, boardMinute, headsign)
                            best[s] = t
                            if (!marked[s]) improved += s
                            marked[s] = true
                        }
                    }
                    // Catch an earlier vehicle here?
                    val ready = arrival(k - 1, s)
                    if (ready == NONE) continue
                    // A change needs a minute; the first boarding doesn't.
                    val departure = pattern.nextDeparture(position, ready + if (k > 1) TRANSFER_SLACK else 0) ?: continue
                    val current = if (boardPosition >= 0) boardMinute + pattern.offsets[position] - pattern.offsets[boardPosition] else NONE
                    if (departure.minute < current) {
                        boardPosition = position
                        boardMinute = departure.minute
                        reach = departure.reach
                        headsign = departure.headsign
                    }
                }
            }

            // Walk on from stops reached by a ride this round.
            for (s in improved) {
                val from = rideArrival[k][s]
                for ((neighbour, minutes) in network.transfers[s]) {
                    val t = from + minutes
                    if (t < best[neighbour] && t < bestAtTarget) {
                        walkArrival[k][neighbour] = t
                        walkFrom[k][neighbour] = s
                        best[neighbour] = t
                        marked[neighbour] = true
                    }
                }
            }
            for ((s, minutes) in egress) {
                val at = rideArrival[k][s]
                if (at != NONE) bestAtTarget = minOf(bestAtTarget, at + minutes)
            }
            if (marked.none { it }) break
        }

        // One answer per number of rides, each faster than all with fewer rides.
        val journeys = ArrayList<Journey>()
        var fastest = NONE
        val direct = distanceMeters(origin, destination)
        if (direct <= MAX_DIRECT_WALK_METERS) {
            val minutes = TransitNetwork.walkMinutes(direct)
            journeys += Journey(startMinute, startMinute + minutes, listOf(walk(origin, destination, null, null, startMinute, startMinute + minutes)))
            fastest = startMinute + minutes
        }
        for (k in 1 until rounds) {
            var bestStop = -1
            var bestTime = NONE
            for ((s, minutes) in egress) {
                val at = rideArrival[k][s]
                if (at != NONE && at + minutes < bestTime) {
                    bestTime = at + minutes
                    bestStop = s
                }
            }
            // Another change has to be worth it.
            val needed = if (journeys.any { it.rides.isNotEmpty() }) fastest - MIN_GAIN_PER_CHANGE else fastest
            if (bestStop < 0 || bestTime >= needed) continue
            fastest = bestTime
            journeys += reconstruct(k, bestStop, bestTime, origin, destination, startMinute, rideArrival, rideFrom, walkArrival, walkFrom)
        }
        return journeys.sortedWith(compareBy({ it.arrivalMinute }, { it.rides.size }))
    }

    private fun nearbyOrWider(point: LatLng): List<Pair<Int, Double>> =
        network.stopsNear(point, ACCESS_RADIUS_METERS).ifEmpty { network.stopsNear(point, WIDE_ACCESS_RADIUS_METERS) }

    private class RideLabel(val pattern: Int, val boardPosition: Int, val alightPosition: Int, val boardMinute: Int, val headsign: String)

    private fun reconstruct(
        rides: Int,
        lastStop: Int,
        arrivalAtTarget: Int,
        origin: LatLng,
        destination: LatLng,
        startMinute: Int,
        rideArrival: Array<IntArray>,
        rideFrom: Array<Array<RideLabel?>>,
        walkArrival: Array<IntArray>,
        walkFrom: Array<IntArray>,
    ): Journey {
        val stops = network.stops
        val legs = ArrayList<JourneyLeg>()
        var k = rides
        var s = lastStop
        val arriveAtStop = rideArrival[k][s]
        legs += walk(stops[s].location, destination, stops[s], null, arriveAtStop, arrivalAtTarget)
        while (true) {
            if (k == 0) {
                legs += walk(origin, stops[s].location, null, stops[s], startMinute, walkArrival[0][s])
                break
            }
            if (walkArrival[k][s] < rideArrival[k][s]) {
                val from = walkFrom[k][s]
                legs += walk(stops[from].location, stops[s].location, stops[from], stops[s], rideArrival[k][from], walkArrival[k][s])
                s = from
            }
            val label = rideFrom[k][s] ?: error("No ride into stop ${stops[s].code} in round $k")
            legs += ride(label, rideArrival[k][s])
            s = network.patterns[label.pattern].stops[label.boardPosition]
            k -= 1
        }
        legs.reverse()
        // Leave as late as still makes the first ride: the start walk ends at boarding.
        val firstRide = legs.firstOrNull { it is JourneyLeg.Ride } as JourneyLeg.Ride?
        val adjusted = if (firstRide != null && legs.first() is JourneyLeg.Walk) {
            val access = legs.first() as JourneyLeg.Walk
            val minutes = access.endMinute - access.startMinute
            listOf(access.copy(startMinute = firstRide.startMinute - minutes, endMinute = firstRide.startMinute)) + legs.drop(1)
        } else {
            legs
        }
        // Drop zero-length walks (boarding right where the trip starts).
        val cleaned = adjusted.filterNot { it is JourneyLeg.Walk && it.meters < 1 && it.fromStop == null && it.toStop != null }
        return Journey(cleaned.first().startMinute, arrivalAtTarget, cleaned)
    }

    private fun ride(label: RideLabel, arrival: Int): JourneyLeg.Ride {
        val pattern = network.patterns[label.pattern]
        val stops = (label.boardPosition..label.alightPosition).map { network.stops[pattern.stops[it]] }
        val path = if (pattern.path != null && pattern.along != null) {
            pattern.path.slice(pattern.along[label.boardPosition], pattern.along[label.alightPosition])
        } else {
            stops.map { it.location }
        }
        // Buses are signed with their line's end point; rail and ferry carry the timetable's.
        val headsign = label.headsign.ifBlank {
            val end = if (pattern.direction == com.aijec.izmirtransit.core.model.Direction.OUTBOUND) pattern.line.destination else pattern.line.origin
            end.ifBlank { network.stops[pattern.stops.last()].name }
        }
        return JourneyLeg.Ride(
            line = pattern.line,
            direction = pattern.direction,
            board = stops.first(),
            alight = stops.last(),
            stops = stops,
            path = path,
            headsign = headsign,
            startMinute = label.boardMinute,
            endMinute = arrival,
            exactTimes = pattern.exact,
        )
    }

    private fun walk(from: LatLng, to: LatLng, fromStop: com.aijec.izmirtransit.core.model.Stop?, toStop: com.aijec.izmirtransit.core.model.Stop?, start: Int, end: Int) =
        JourneyLeg.Walk(from, to, fromStop, toStop, (distanceMeters(from, to) * TransitNetwork.WALK_DETOUR).toInt(), start, end)

    companion object {
        private const val NONE = Int.MAX_VALUE / 2
        private const val ORIGIN = -2
        const val MAX_RIDES = 4
        const val TRANSFER_SLACK = 1
        /** A trip with one more change is only offered if it arrives at least this much sooner. */
        const val MIN_GAIN_PER_CHANGE = 5
        /** How far people walk to the first stop and from the last one. */
        const val ACCESS_RADIUS_METERS = 900.0
        const val WIDE_ACCESS_RADIUS_METERS = 2_000.0
        /** Within this, walking all the way is offered too. */
        const val MAX_DIRECT_WALK_METERS = 2_500.0
    }
}
