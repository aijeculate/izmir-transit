package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.EtaBasis
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.test.Test
import kotlin.test.assertEquals

class EtaEstimatorTest {
    private val estimator = EtaEstimator()

    // East-west route about 5 km long; 0.0115° of longitude ≈ 1 km at this latitude.
    private val route = MeasuredPolyline(listOf(LatLng(38.4, 27.1), LatLng(38.4, 27.1575)))
    private fun km(x: Double) = LatLng(38.4, 27.1 + 0.0115 * x)

    @Test
    fun `uses distance along the route`() {
        val eta = estimator.estimate(bus = km(1.0), stopsRemaining = 5, stop = km(3.0), route = route)
        assertEquals(EtaBasis.ROUTE_DISTANCE, eta.basis)
        assertEquals(7, eta.minutes) // ~2 km at 300 m/min
    }

    @Test
    fun `bus at the stop is zero minutes even with GPS jitter past it`() {
        val eta = estimator.estimate(bus = km(3.02), stopsRemaining = 0, stop = km(3.0), route = route)
        assertEquals(0, eta.minutes)
        assertEquals(EtaBasis.ROUTE_DISTANCE, eta.basis)
    }

    @Test
    fun `bus well past the stop on this shape falls back to stop count`() {
        val eta = estimator.estimate(bus = km(4.0), stopsRemaining = 2, stop = km(3.0), route = route)
        assertEquals(EtaBasis.STOP_COUNT, eta.basis)
        assertEquals(3, eta.minutes)
    }

    @Test
    fun `far bus with few stops remaining is flagged unreliable`() {
        // Real case: a line 29 bus 10 km away reported as 2 stops from stop 10007.
        val eta = estimator.estimate(
            bus = LatLng(38.45950833, 27.24435167), stopsRemaining = 2,
            stop = LatLng(38.415144105211, 27.1277200912719), route = null,
        )
        assertEquals(EtaBasis.UNRELIABLE, eta.basis)
    }

    @Test
    fun `no stop location uses stop count`() {
        assertEquals(EtaBasis.STOP_COUNT, estimator.estimate(km(0.0), 4, stop = null, route = null).basis)
    }
}
