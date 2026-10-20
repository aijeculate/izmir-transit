package com.aijec.izmirtransit.core.data.alerts

import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.source.eshot.live.RawApproachingBus
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveError
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrivalWatchTest {
    private fun bus(id: Int, stops: Int) =
        RawApproachingBus(id, "818", "", Direction.OUTBOUND, stops, LatLng(38.4, 27.1), wheelchairAccessible = true, bikeRack = false)

    private fun poll(vararg buses: RawApproachingBus) = FetchResult.Success(buses.toList())

    @Test
    fun `tracks the chosen bus and alerts at the threshold`() {
        val watch = ArrivalWatch(busId = 7, alertAtStops = 2)
        assertEquals(ArrivalWatch.Step.Tracking(6), watch.onResult(poll(bus(7, 6), bus(8, 1))))
        assertEquals(ArrivalWatch.Step.Tracking(3), watch.onResult(poll(bus(7, 3))))
        assertEquals(ArrivalWatch.Step.Alert(2), watch.onResult(poll(bus(7, 2))))
    }

    @Test
    fun `other buses at the threshold do not trigger it`() {
        val watch = ArrivalWatch(busId = 7, alertAtStops = 2)
        assertEquals(ArrivalWatch.Step.Tracking(9), watch.onResult(poll(bus(8, 1), bus(7, 9))))
    }

    @Test
    fun `failed polls and single gaps keep waiting`() {
        val watch = ArrivalWatch(busId = 7, alertAtStops = 2)
        watch.onResult(poll(bus(7, 6)))
        assertEquals(ArrivalWatch.Step.Waiting, watch.onResult(FetchResult.Failure(LiveError.NETWORK, retryable = true)))
        assertEquals(ArrivalWatch.Step.Waiting, watch.onResult(poll()))
        assertEquals(ArrivalWatch.Step.Tracking(5), watch.onResult(poll(bus(7, 5))))
    }

    @Test
    fun `a bus gone for three polls is lost, and likely arrived if it was next to the stop`() {
        val far = ArrivalWatch(busId = 7, alertAtStops = 0)
        far.onResult(poll(bus(7, 6)))
        repeat(2) { assertEquals(ArrivalWatch.Step.Waiting, far.onResult(poll())) }
        assertEquals(ArrivalWatch.Step.Lost(likelyArrived = false), far.onResult(poll()))

        val near = ArrivalWatch(busId = 7, alertAtStops = 0)
        near.onResult(poll(bus(7, 1)))
        repeat(2) { near.onResult(poll()) }
        assertEquals(ArrivalWatch.Step.Lost(likelyArrived = true), near.onResult(poll()))
    }
}
