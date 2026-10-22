package com.aijec.izmirtransit.core.data.repository

import com.aijec.izmirtransit.core.model.ServiceDay
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidaysTest {
    @Test
    fun `holidays run the Sunday timetable, an arife its usual one`() {
        // Thursday 29 Oct 2026, Cumhuriyet Bayramı; Wednesday the 28th is its half-day eve.
        assertEquals(ServiceDay.SUNDAY, TimetableRepository.serviceDayFor(LocalDate.of(2026, 10, 29)))
        assertTrue(Holidays.on(LocalDate.of(2026, 10, 28))!!.eve)
        assertEquals(ServiceDay.WEEKDAY, TimetableRepository.serviceDayFor(LocalDate.of(2026, 10, 28)))
        // Kurban Bayramı 2027: arife Saturday 15 May, bayram Sunday 16 to Wednesday 19 May.
        assertEquals(ServiceDay.SATURDAY, TimetableRepository.serviceDayFor(LocalDate.of(2027, 5, 15)))
        assertEquals(ServiceDay.SUNDAY, TimetableRepository.serviceDayFor(LocalDate.of(2027, 5, 19)))
        assertEquals(ServiceDay.WEEKDAY, TimetableRepository.serviceDayFor(LocalDate.of(2027, 5, 20)))
        // After midnight on a holiday night, the trains still running are the holiday's.
        assertEquals(ServiceDay.SUNDAY, TimetableRepository.serviceMoment(LocalDateTime.of(2026, 10, 30, 0, 30)).serviceDay)
        assertNull(Holidays.on(LocalDate.of(2026, 10, 27)))
    }

    @Test
    fun `later bayrams come from the website file`() {
        val spans = Holidays.parse(
            """{"bayrams":[{"name":"Ramazan Bayramı","start":"2028-02-26","days":3},{"name":"bad","start":"soon","days":3}]}""",
        )
        assertEquals(1, spans.size)
        Holidays.update(spans)
        assertEquals(ServiceDay.SUNDAY, TimetableRepository.serviceDayFor(LocalDate.of(2028, 2, 28)))
        assertTrue(Holidays.on(LocalDate.of(2028, 2, 25))!!.eve)
        Holidays.update(emptyList())
    }
}
