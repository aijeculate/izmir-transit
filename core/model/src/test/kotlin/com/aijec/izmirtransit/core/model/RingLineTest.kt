package com.aijec.izmirtransit.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingLineTest {
    private fun bus(notes: String) = Line(
        mode = TransitMode.BUS, code = "465", name = "TINAZTEPE - KONAK",
        routeDescription = "", notes = notes, origin = "TINAZTEPE", destination = "KONAK",
    )

    @Test
    fun `ESHOT's ring note marks a ring line, in any case`() {
        assertTrue(bus("HATTIMIZ RİNG HAT OLUP KONAKTAN SAAT UYGULAMASI YOKTUR").isRing)
        assertTrue(bus("Hattımız ring hat olup Konaktan saat uygulaması yoktur.").isRing)
    }

    @Test
    fun `a line that only rings at rush hour is not a ring line`() {
        // Line 566: "Hattımız 06:00 - 09:00 saatleri arasında ring yapmaktadır."
        assertFalse(bus("Hattımız 06:00 - 09:00 saatleri arasında ring yapmaktadır.").isRing)
        assertFalse(bus("").isRing)
    }

    @Test
    fun `a ring starts in its only timed direction`() {
        val ring = bus("HATTIMIZ RİNG HAT OLUP KONAKTAN SAAT UYGULAMASI YOKTUR")
        assertEquals(Direction.OUTBOUND, ring.ringStart(listOf(Direction.OUTBOUND)))
        // Line 21 is timed from Konak, its return direction.
        assertEquals(Direction.RETURN, ring.ringStart(listOf(Direction.RETURN)))
        // Timed both ways: the note is out of date; treat it as an ordinary line.
        assertNull(ring.ringStart(listOf(Direction.OUTBOUND, Direction.RETURN)))
        assertNull(bus("").ringStart(listOf(Direction.OUTBOUND)))
    }
}
