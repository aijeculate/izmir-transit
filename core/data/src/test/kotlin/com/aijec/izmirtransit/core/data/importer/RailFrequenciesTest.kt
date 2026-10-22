package com.aijec.izmirtransit.core.data.importer

import com.aijec.izmirtransit.core.model.ServiceDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailFrequenciesTest {
    // Shaped like metrotramapi.iztek.tr/api/v1/line-schedules on 23 Oct 2026.
    private val json = """
        {"data":[
          {"lineName":"Konak Tramvayı","dayTypeName":"Hafta İçi","startTime":"21:30:00","endTime":"00:00:00","frequencyInMinutes":12},
          {"lineName":"Konak Tramvayı","dayTypeName":"Hafta İçi","startTime":"00:00:00","endTime":"00:20:00","frequencyInMinutes":10},
          {"lineName":"Konak Tramvayı","dayTypeName":"Pazar","startTime":"06:00:00","endTime":"06:30:00","frequencyInMinutes":7.5},
          {"lineName":"Konak Tramvayı","dayTypeName":"Bayram","startTime":"06:00:00","endTime":"07:00:00","frequencyInMinutes":10}
        ],"isSuccessful":true}
    """

    @Test
    fun `parses bands onto the service day's clock`() {
        val bands = RailFrequencies.parse(json)
        assertEquals(3, bands.size) // an unknown day type is skipped
        assertEquals(RailFrequencies.Band("Konak Tramvayı", ServiceDay.WEEKDAY, 21 * 60 + 30, 24 * 60, 12.0), bands[0])
        assertEquals(RailFrequencies.Band("Konak Tramvayı", ServiceDay.WEEKDAY, 24 * 60, 24 * 60 + 20, 10.0), bands[1])
    }

    @Test
    fun `lays departures out band after band, the last one including its end`() {
        val bands = RailFrequencies.parse(json)
        val weekday = RailFrequencies.departures(bands.filter { it.day == ServiceDay.WEEKDAY })
        assertEquals(listOf(1290, 1302, 1314, 1326, 1338, 1350, 1362, 1374, 1386, 1398, 1410, 1422, 1434, 1440, 1450, 1460), weekday)
        // Every 7.5 minutes rounds to whole minutes.
        assertEquals(listOf(360, 368, 375, 383, 390), RailFrequencies.departures(bands.filter { it.day == ServiceDay.SUNDAY }))
    }

    @Test
    fun `matches lines and pattern ends across naming differences`() {
        assertEquals("T-TURUNCU", RailFrequencies.lineCodeFor(RailFeed.TRAM, "Karşıyaka Ring Tramvayı (Turuncu)", listOf("T-KONAK", "T-TURUNCU")))
        assertNull(RailFrequencies.lineCodeFor(RailFeed.TRAM, "Çiğli Tramvayı Dış Hat ( Kırmızı ) Sefer Planı", listOf("T-CIGLI_DIS")))
        assertEquals("M1", RailFrequencies.lineCodeFor(RailFeed.METRO, "Evka - 3 / Narlıdere Kaymakamlık", listOf("M1")))

        val ends = RailFrequencies.terminals("Evka - 3 / Narlıdere Kaymakamlık")!!
        assertTrue(RailFrequencies.sameEnds(ends, "Kaymakamlık", "Evka - 3"))
        assertTrue(RailFrequencies.sameEnds(ends, "Evka-3", "Narlıdere Kaymakamlık"))
        assertFalse(RailFrequencies.sameEnds(ends, "Evka - 3", "Fahrettin Altay"))
        assertNull(RailFrequencies.terminals("Konak Tramvayı"))
    }
}
