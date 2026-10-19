package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.Eta
import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import com.aijec.izmirtransit.core.model.geo.distanceMeters
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Estimates minutes until a bus reaches a stop. The API gives only `KalanDurakSayisi`
 * (stops remaining), which can be stale: a bus 10 km away was reported as 2 stops out.
 * So the distance along the route is the primary signal, and the two are cross-checked.
 */
class EtaEstimator(
    /** Average urban bus speed including stops, about 18 km/h. Tune with daytime data. */
    private val metersPerMinute: Double = 300.0,
    private val minutesPerStop: Double = 1.5,
    /** Anything beyond this per remaining stop (plus slack) is treated as inconsistent. */
    private val maxMetersPerStop: Double = 1500.0,
    private val slackMeters: Double = 1000.0,
    private val maxDistanceFromRouteMeters: Double = 300.0,
) {
    fun estimate(bus: LatLng, stopsRemaining: Int, stop: LatLng?, route: MeasuredPolyline?): Eta {
        val limit = stopsRemaining.coerceAtLeast(1) * maxMetersPerStop + slackMeters
        if (stop != null && route != null) {
            val busOnRoute = route.project(bus)
            val stopOnRoute = route.project(stop)
            if (busOnRoute != null && stopOnRoute != null &&
                busOnRoute.distanceFromLineMeters <= maxDistanceFromRouteMeters &&
                stopOnRoute.distanceFromLineMeters <= maxDistanceFromRouteMeters
            ) {
                val ahead = stopOnRoute.distanceAlongMeters - busOnRoute.distanceAlongMeters
                // A small negative value is GPS noise at the stop; a large one means the
                // bus is past the stop on this shape (e.g. a loop), so fall back.
                if (ahead > -100.0) {
                    val meters = ahead.coerceAtLeast(0.0)
                    val basis = if (meters > limit) EtaBasis.UNRELIABLE else EtaBasis.ROUTE_DISTANCE
                    return Eta(ceil(meters / metersPerMinute).toInt(), basis)
                }
            }
        }
        val byStops = (stopsRemaining.coerceAtLeast(0) * minutesPerStop).roundToInt()
        if (stop != null && distanceMeters(bus, stop) > limit) return Eta(byStops, EtaBasis.UNRELIABLE)
        return Eta(byStops, EtaBasis.STOP_COUNT)
    }
}
