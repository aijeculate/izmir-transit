package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A north–south two-way street, about 1.1 km long: outbound runs north, return runs south
 * along the same line, as the 4-decimal shapes do. Stops sit 8 m either side of it.
 */
class StopSideTest {
    private val south = LatLng(38.4000, 27.1400)
    private val north = LatLng(38.4100, 27.1400)
    private val outbound = MeasuredPolyline(listOf(south, north))
    private val inbound = MeasuredPolyline(listOf(north, south))

    /** 8 m east is the right-hand side for northbound traffic. */
    private val eastStop = LatLng(38.4050, 27.1400 + 8 / (111_320.0 * kotlin.math.cos(38.405 * kotlin.math.PI / 180)))
    private val westStop = LatLng(38.4050, 27.1400 - 8 / (111_320.0 * kotlin.math.cos(38.405 * kotlin.math.PI / 180)))

    private fun belongs(stop: LatLng, here: MeasuredPolyline, other: MeasuredPolyline) =
        EshotImporter.belongsTo(stop, here, here.project(stop)!!, other, other.project(stop)!!)

    @Test
    fun `a stop on the right of travel belongs to that direction only`() {
        assertTrue(belongs(eastStop, outbound, inbound))
        assertFalse(belongs(eastStop, inbound, outbound))
        assertTrue(belongs(westStop, inbound, outbound))
        assertFalse(belongs(westStop, outbound, inbound))
    }

    @Test
    fun `a stop at a terminus is kept for both directions`() {
        val atEnd = LatLng(38.40995, 27.1400 + 0.00008)
        assertTrue(belongs(atEnd, outbound, inbound))
        assertTrue(belongs(atEnd, inbound, outbound))
    }
}
