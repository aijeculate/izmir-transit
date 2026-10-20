package com.aijec.izmirtransit.core.data.alerts

import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.source.eshot.live.RawApproachingBus
import kotlinx.coroutines.flow.StateFlow

/** "Tell me when bus [busId] on [lineCode] is [alertAtStops] stops from [stopCode]." */
data class AlertRequest(
    val busId: Int,
    val lineCode: String,
    val stopCode: String,
    val stopName: String,
    val alertAtStops: Int,
)

/**
 * Starts and stops arrival alerts. Implemented in the app (a foreground service), so
 * feature screens can request one without depending on Android service classes.
 */
interface ArrivalAlerts {
    /** The alert being tracked right now, if any. Only one at a time. */
    val active: StateFlow<AlertRequest?>

    fun start(request: AlertRequest)

    fun stop()
}

/**
 * The decision logic for one arrival alert, kept free of Android so it can be tested.
 * Feed it each poll of `hattinyaklasanotobusleri` for the line and stop.
 */
class ArrivalWatch(
    private val busId: Int,
    private val alertAtStops: Int,
    /** Polls in a row without the bus before we conclude it arrived or left the feed. */
    private val lostAfterMisses: Int = 3,
) {
    sealed interface Step {
        /** Still coming; update the ongoing notification. */
        data class Tracking(val stopsRemaining: Int) : Step

        /** Within the threshold: alert the user and finish. */
        data class Alert(val stopsRemaining: Int) : Step

        /**
         * The bus left the feed. [likelyArrived] when it was last seen at or next to the
         * stop; otherwise its position simply stopped being reported.
         */
        data class Lost(val likelyArrived: Boolean) : Step

        /** A failed poll or a single missing reading: keep the last state, try again. */
        data object Waiting : Step
    }

    private var misses = 0
    private var lastStops: Int? = null

    fun onResult(result: FetchResult<List<RawApproachingBus>>): Step {
        if (result !is FetchResult.Success) return Step.Waiting
        val bus = result.value.firstOrNull { it.busId == busId }
        if (bus == null) {
            misses++
            return if (misses >= lostAfterMisses) Step.Lost(likelyArrived = (lastStops ?: Int.MAX_VALUE) <= 1) else Step.Waiting
        }
        misses = 0
        lastStops = bus.stopsRemaining
        return if (bus.stopsRemaining <= alertAtStops) Step.Alert(bus.stopsRemaining) else Step.Tracking(bus.stopsRemaining)
    }
}
