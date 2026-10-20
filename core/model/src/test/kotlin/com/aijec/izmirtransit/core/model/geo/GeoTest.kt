package com.aijec.izmirtransit.core.model.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun `distance between Konak and Alsancak is about 2_3 km`() {
        val konak = LatLng(38.4189, 27.1287)
        val alsancak = LatLng(38.4381, 27.1418)
        assertEquals(2400.0, distanceMeters(konak, alsancak), 150.0)
    }

    @Test
    fun `projection measures distance along the line and from it`() {
        // A straight east-west line about 1 km long.
        val line = MeasuredPolyline(listOf(LatLng(38.4, 27.1), LatLng(38.4, 27.1115)))
        assertEquals(1000.0, line.lengthMeters, 10.0)

        val projection = assertNotNull(line.project(LatLng(38.4009, 27.10575)))
        assertEquals(500.0, projection.distanceAlongMeters, 10.0)
        assertEquals(100.0, projection.distanceFromLineMeters, 3.0)
    }

    @Test
    fun `pointAt walks along the line and clamps at the ends`() {
        val line = MeasuredPolyline(listOf(LatLng(38.4, 27.1), LatLng(38.4, 27.1115), LatLng(38.41, 27.1115)))
        val half = line.pointAt(line.lengthMeters / 4)
        assertEquals(38.4, half.latitude, 1e-9)
        assertTrue(half.longitude > 27.1 && half.longitude < 27.1115)
        assertEquals(LatLng(38.4, 27.1), line.pointAt(-5.0))
        assertEquals(LatLng(38.41, 27.1115), line.pointAt(line.lengthMeters + 100))
        // Round trip with project().
        val p = line.pointAt(1500.0)
        assertEquals(1500.0, assertNotNull(line.project(p)).distanceAlongMeters, 1.0)
    }

    @Test
    fun `polyline codec round-trips to 1e-5`() {
        val points = listOf(LatLng(38.45342667, 27.097125), LatLng(38.3971, 27.06865833), LatLng(38.4, 26.99))
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertEquals(a.latitude, b.latitude, 1e-5)
            assertEquals(a.longitude, b.longitude, 1e-5)
        }
    }

    @Test
    fun `polyline codec matches Google's reference example`() {
        val points = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(points))
    }

    @Test
    fun `simplify drops collinear and duplicate points but keeps corners`() {
        val points = listOf(
            LatLng(38.4, 27.1), LatLng(38.4, 27.1), LatLng(38.4, 27.105), LatLng(38.4, 27.11),
            LatLng(38.41, 27.11),
        )
        val simplified = simplifyPolyline(points, toleranceMeters = 3.0)
        assertEquals(listOf(LatLng(38.4, 27.1), LatLng(38.4, 27.11), LatLng(38.41, 27.11)), simplified)
    }

    @Test
    fun `izmir bounding box rejects the zero coordinate ghost`() {
        assertTrue(LatLng(38.42, 27.14) in BoundingBox.IZMIR)
        assertTrue(LatLng(0.0, 0.0) !in BoundingBox.IZMIR)
        // Swapped lat/lon also falls outside.
        assertTrue(LatLng(27.14, 38.42) !in BoundingBox.IZMIR)
    }
}
