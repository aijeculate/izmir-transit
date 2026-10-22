package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.aijec.izmirtransit.core.model.geo.MeasuredPolyline
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionResolverTest {
    // Outbound runs west to east along latitude 38.40 and then north; return comes back
    // south and then west along the same street.
    private val shared = listOf(LatLng(38.40, 27.10), LatLng(38.40, 27.12))
    private val outbound = MeasuredPolyline(shared + LatLng(38.42, 27.12))
    private val returning = MeasuredPolyline(listOf(LatLng(38.42, 27.125), LatLng(38.40, 27.125)) + shared.reversed())
    private val routes = mapOf(Direction.OUTBOUND to outbound, Direction.RETURN to returning)

    private fun bus(lat: Double, lng: Double, yon: Direction) = LiveBus(1, "233", yon, LatLng(lat, lng))

    @Test
    fun `near only one route wins over the feed's direction`() {
        val resolver = DirectionResolver()
        // On the outbound-only northern leg, but the feed says return.
        val result = resolver.resolve(listOf(bus(38.41, 27.12, Direction.RETURN)), routes)
        assertEquals(Direction.OUTBOUND, result.single().direction)
    }

    @Test
    fun `on a shared street, the direction it moves forward along`() {
        val resolver = DirectionResolver()
        // Moving west along the shared street: forward on the return shape.
        resolver.resolve(listOf(bus(38.40, 27.115, Direction.OUTBOUND)), routes)
        val moved = resolver.resolve(listOf(bus(38.40, 27.112, Direction.OUTBOUND)), routes)
        assertEquals(Direction.RETURN, moved.single().direction)
        // Standing still keeps what was worked out.
        val still = resolver.resolve(listOf(bus(38.40, 27.112, Direction.OUTBOUND)), routes)
        assertEquals(Direction.RETURN, still.single().direction)
    }

    @Test
    fun `first sighting on a shared street trusts the feed`() {
        val resolver = DirectionResolver()
        val result = resolver.resolve(listOf(bus(38.40, 27.11, Direction.OUTBOUND)), routes)
        assertEquals(Direction.OUTBOUND, result.single().direction)
    }

    @Test
    fun `one-direction lines are left alone`() {
        val resolver = DirectionResolver()
        val result = resolver.resolve(listOf(bus(38.41, 27.12, Direction.RETURN)), mapOf(Direction.OUTBOUND to outbound))
        assertEquals(Direction.RETURN, result.single().direction)
    }
}
