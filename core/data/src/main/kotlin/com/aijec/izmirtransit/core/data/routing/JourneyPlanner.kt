package com.aijec.izmirtransit.core.data.routing

import com.aijec.izmirtransit.core.model.Journey
import com.aijec.izmirtransit.core.model.JourneyLeg
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.distanceMeters

/**
 * Trip planning with multi-criteria RAPTOR (McRAPTOR; Delling et al., "Round-Based Public
 * Transit Routing"). Round k finds every trip with k rides that isn't beaten on both
 * arrival time and walking by another with as few rides; between rounds people walk to
 * nearby stops. Keeping walking as a criterion finds the "same time, much less walking"
 * trip that earliest-arrival RAPTOR missed (Gaziemir → Menemen chose a 2.1 km walk).
 *
 * The result is a short list: the fastest trip, plus ones with fewer changes or much less
 * walking that cost little extra time.
 */
class JourneyPlanner(private val network: TransitNetwork) {

    /** A way to reach a stop: when, how far walked so far, and the step that got there. */
    private class Label(
        val stop: Int,
        val arrival: Int,
        val walk: Int,
        val parent: Label?,
        /** The ride into [stop], or null for a walk. */
        val ride: RideLabel?,
        /** For a walk: where it started (a stop, or [ORIGIN]). */
        val walkFrom: Int,
    )

    private class RideLabel(val pattern: Int, val boardPosition: Int, val alightPosition: Int, val boardMinute: Int, val headsign: String)

    /** A trip being ridden along a pattern during a scan. */
    private class Boarded(val from: Label, val position: Int, val minute: Int, val reach: Int, val headsign: String)

    private class Candidate(val label: Label, val rides: Int, val arrival: Int, val walk: Int, val egressStop: Int)

    fun plan(origin: LatLng, destination: LatLng, startMinute: Int, maxRides: Int = MAX_RIDES): List<Journey> {
        val stopCount = network.stops.size
        val rounds = maxRides + 1
        val bags = Array(rounds) { arrayOfNulls<MutableList<Label>>(stopCount) }
        // Every label kept so far at a stop, any round: a later round must beat these.
        val best = arrayOfNulls<MutableList<Label>>(stopCount)
        val target = ArrayList<Pair<Int, Int>>() // (arrival, walk) reaching the destination
        val candidates = ArrayList<Candidate>()

        fun prunedByTarget(arrival: Int, walk: Int) = target.any { (a, w) -> a <= arrival && w <= walk }

        /** Adds a label to round [k]'s bag at its stop unless something already kept is as good. */
        fun insert(k: Int, label: Label): Boolean {
            if (prunedByTarget(label.arrival, label.walk)) return false
            val all = best[label.stop] ?: ArrayList<Label>().also { best[label.stop] = it }
            if (all.any { dominates(it.arrival, it.walk, label.arrival, label.walk) }) return false
            all.removeAll { dominates(label.arrival, label.walk, it.arrival, it.walk) }
            all += label
            val bag = bags[k][label.stop] ?: ArrayList<Label>().also { bags[k][label.stop] = it }
            bag.removeAll { dominates(label.arrival, label.walk, it.arrival, it.walk) }
            bag += label
            return true
        }

        // Round 0: walk from the origin to stops around it.
        val marked = BooleanArray(stopCount)
        for ((s, meters) in nearbyOrWider(origin)) {
            val label = Label(s, startMinute + TransitNetwork.walkMinutes(meters), walkedMeters(meters), null, null, ORIGIN)
            if (insert(0, label)) marked[s] = true
        }
        val egress = nearbyOrWider(destination).map { (s, meters) -> Triple(s, TransitNetwork.walkMinutes(meters), walkedMeters(meters)) }

        for (k in 1 until rounds) {
            // Patterns to scan, from the earliest stop marked last round.
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
            val rodeTo = ArrayList<Label>()

            for ((p, first) in scanFrom) {
                val pattern = network.patterns[p]
                val boarded = ArrayList<Boarded>()
                for (position in first until pattern.stops.size) {
                    val s = pattern.stops[position]
                    // Ride on to this stop.
                    for (trip in boarded) {
                        if (position > trip.reach) continue
                        val t = trip.minute + pattern.offsets[position] - pattern.offsets[trip.position]
                        val label = Label(s, t, trip.from.walk, trip.from, RideLabel(p, trip.position, position, trip.minute, trip.headsign), -1)
                        if (insert(k, label)) {
                            marked[s] = true
                            rodeTo += label
                        }
                    }
                    // Board here from anything that reached this stop last round.
                    val ready = bags[k - 1][s] ?: continue
                    for (from in ready) {
                        // A change needs a minute; the first boarding doesn't.
                        val departure = pattern.nextDeparture(position, from.arrival + if (k > 1) TRANSFER_SLACK else 0) ?: continue
                        val trip = Boarded(from, position, departure.minute, departure.reach, departure.headsign)
                        // Keep the trips that are best on time-here or walking so far.
                        fun here(b: Boarded) = b.minute + pattern.offsets[position] - pattern.offsets[b.position]
                        if (boarded.any { dominates(here(it), it.from.walk, here(trip), trip.from.walk) && it.reach >= trip.reach }) continue
                        boarded.removeAll { dominates(here(trip), trip.from.walk, here(it), it.from.walk) && trip.reach >= it.reach }
                        boarded += trip
                    }
                }
            }

            // Walk on from stops reached by a ride this round.
            for (ride in rodeTo) {
                for ((neighbour, minutes) in network.transfers[ride.stop]) {
                    val meters = walkedMeters(distanceMeters(network.stops[ride.stop].location, network.stops[neighbour].location))
                    val label = Label(neighbour, ride.arrival + minutes, ride.walk + meters, ride, null, ride.stop)
                    if (insert(k, label)) marked[neighbour] = true
                }
            }
            for ((s, minutes, meters) in egress) {
                for (label in bags[k][s].orEmpty()) {
                    if (label.ride == null) continue
                    val arrival = label.arrival + minutes
                    val walk = label.walk + meters
                    if (prunedByTarget(arrival, walk)) continue
                    target.removeAll { (a, w) -> arrival <= a && walk <= w }
                    target += arrival to walk
                    candidates += Candidate(label, k, arrival, walk, s)
                }
            }
            if (marked.none { it }) break
        }

        return choose(candidates, origin, destination, startMinute)
    }

    /**
     * Trips that arrive by [arriveBy], leaving as late as possible. Leaving later never
     * arrives earlier, so a binary search over the start time finds the latest start that
     * still makes it; a second search a little earlier adds alternatives.
     */
    fun planArriveBy(origin: LatLng, destination: LatLng, arriveBy: Int, maxRides: Int = MAX_RIDES): List<Journey> {
        fun inTime(start: Int) = plan(origin, destination, start, maxRides).filter { it.arrivalMinute <= arriveBy }
        var low = arriveBy - ARRIVE_BY_LOOKBACK_MINUTES
        var high = arriveBy
        var found = inTime(low)
        if (found.isEmpty()) return emptyList()
        while (high - low > 1) {
            val mid = (low + high) / 2
            val options = inTime(mid)
            if (options.isNotEmpty()) {
                low = mid
                found = options
            } else {
                high = mid
            }
        }
        val earlier = inTime(low - ARRIVE_BY_ALTERNATIVES_MINUTES)
        return (found + earlier)
            .groupBy { journey -> journey.rides.joinToString { "${it.line.code}@${it.board.code}" } }
            .map { (_, same) -> same.maxBy { it.departureMinute } }
            .sortedWith(compareByDescending<Journey> { it.departureMinute }.thenBy { it.rides.size })
            .take(MAX_OPTIONS)
    }

    /**
     * The fastest trip, then trips with fewer rides or much less walking that arrive at most
     * [MAX_SLOWER_MINUTES] later; another change has to save [MIN_GAIN_PER_CHANGE] minutes.
     */
    private fun choose(candidates: List<Candidate>, origin: LatLng, destination: LatLng, startMinute: Int): List<Journey> {
        val journeys = ArrayList<Journey>()
        var fastest = NONE
        var leastWalking = Int.MAX_VALUE
        var fewestRides = Int.MAX_VALUE
        val direct = distanceMeters(origin, destination)
        if (direct <= MAX_DIRECT_WALK_METERS) {
            val minutes = TransitNetwork.walkMinutes(direct)
            journeys += Journey(startMinute, startMinute + minutes, listOf(walk(origin, destination, null, null, startMinute, startMinute + minutes)))
            fastest = startMinute + minutes
            leastWalking = walkedMeters(direct)
            fewestRides = 0
        }
        // Fewest rides first, then fastest: each must earn its place against what's kept.
        for (c in candidates.sortedWith(compareBy({ it.rides }, { it.arrival }, { it.walk }))) {
            if (journeys.size >= MAX_OPTIONS) break
            val worthIt = when {
                journeys.none { it.rides.isNotEmpty() } && fewestRides == Int.MAX_VALUE -> true
                // Faster enough to be worth this many rides.
                c.arrival <= fastest - (if (c.rides > fewestRides) MIN_GAIN_PER_CHANGE else 1) -> true
                // About as fast, much less walking, and at most one more change.
                c.arrival <= fastest + MAX_SLOWER_MINUTES && c.walk <= leastWalking - MIN_WALK_SAVED_METERS && c.rides <= fewestRides + 1 -> true
                // Transit beats a long walk that takes about as long.
                journeys.none { it.rides.isNotEmpty() } && c.arrival <= fastest + MAX_SLOWER_MINUTES && c.walk <= leastWalking / 2 -> true
                else -> false
            }
            if (!worthIt) continue
            journeys += reconstruct(c, origin, destination, startMinute)
            fastest = minOf(fastest, c.arrival)
            leastWalking = minOf(leastWalking, c.walk)
            fewestRides = minOf(fewestRides, c.rides)
        }
        return journeys.sortedWith(compareBy({ it.arrivalMinute }, { it.rides.size }))
    }

    private fun nearbyOrWider(point: LatLng): List<Pair<Int, Double>> =
        network.stopsNear(point, ACCESS_RADIUS_METERS).ifEmpty { network.stopsNear(point, WIDE_ACCESS_RADIUS_METERS) }

    private fun reconstruct(candidate: Candidate, origin: LatLng, destination: LatLng, startMinute: Int): Journey {
        val stops = network.stops
        val legs = ArrayList<JourneyLeg>()
        val last = candidate.label
        legs += walk(stops[last.stop].location, destination, stops[last.stop], null, last.arrival, candidate.arrival)
        var label: Label? = last
        while (label != null) {
            val ride = label.ride
            val parent = label.parent
            when {
                ride != null -> legs += ride(ride, label.arrival)
                label.walkFrom == ORIGIN -> legs += walk(origin, stops[label.stop].location, null, stops[label.stop], startMinute, label.arrival)
                parent != null -> legs += walk(stops[parent.stop].location, stops[label.stop].location, stops[parent.stop], stops[label.stop], parent.arrival, label.arrival)
            }
            label = parent
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
        return Journey(cleaned.first().startMinute, candidate.arrival, cleaned)
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

    private fun walk(from: LatLng, to: LatLng, fromStop: Stop?, toStop: Stop?, start: Int, end: Int) =
        JourneyLeg.Walk(from, to, fromStop, toStop, walkedMeters(distanceMeters(from, to)), start, end)

    companion object {
        private const val NONE = Int.MAX_VALUE / 2
        private const val ORIGIN = -2
        const val MAX_RIDES = 4
        const val TRANSFER_SLACK = 1
        /** A trip with more rides is only offered if it arrives at least this much sooner. */
        const val MIN_GAIN_PER_CHANGE = 5
        /** A slower trip is offered for fewer rides or [MIN_WALK_SAVED_METERS] less walking, up to this much later. */
        const val MAX_SLOWER_MINUTES = 10
        const val MIN_WALK_SAVED_METERS = 500
        /** Walking differences below this don't make two labels different (keeps bags small). */
        const val WALK_TOLERANCE_METERS = 100
        const val MAX_OPTIONS = 5
        /** How far people walk to the first stop and from the last one. */
        const val ACCESS_RADIUS_METERS = 1_200.0
        const val WIDE_ACCESS_RADIUS_METERS = 2_000.0
        /** Within this, walking all the way is offered too. */
        const val MAX_DIRECT_WALK_METERS = 2_500.0
        const val ARRIVE_BY_LOOKBACK_MINUTES = 180
        const val ARRIVE_BY_ALTERNATIVES_MINUTES = 15

        private fun walkedMeters(straightMeters: Double) = (straightMeters * TransitNetwork.WALK_DETOUR).toInt()

        private fun dominates(arrivalA: Int, walkA: Int, arrivalB: Int, walkB: Int) =
            arrivalA <= arrivalB && walkA <= walkB + WALK_TOLERANCE_METERS
    }
}
