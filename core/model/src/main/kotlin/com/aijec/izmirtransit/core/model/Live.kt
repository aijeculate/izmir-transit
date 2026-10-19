package com.aijec.izmirtransit.core.model

import com.aijec.izmirtransit.core.model.geo.LatLng
import java.time.Instant

/** A bus currently driving a line, after ghost records have been removed. */
data class LiveBus(
    val busId: Int,
    val lineCode: String,
    val direction: Direction,
    val position: LatLng,
)

/** A bus heading to a particular stop. */
data class ApproachingBus(
    val busId: Int,
    val lineCode: String,
    val lineName: String,
    val direction: Direction,
    val stopsRemaining: Int,
    val position: LatLng,
    val wheelchairAccessible: Boolean,
    val bikeRack: Boolean,
    val eta: Eta,
)

/** The API has no ETA, so every value is an estimate; the UI must label it as approximate. */
data class Eta(val minutes: Int, val basis: EtaBasis)

enum class EtaBasis {
    /** Distance along the route shape. Best estimate. */
    ROUTE_DISTANCE,

    /** Stops remaining times an average; used when the shape or stop location is unknown. */
    STOP_COUNT,

    /** Stop count and distance disagree badly (stale or ghost record). Show with a warning. */
    UNRELIABLE,
}

enum class LiveStatus {
    /** First request in flight, nothing to show yet. */
    LOADING,

    /** Data from the most recent poll. */
    FRESH,

    /** The latest poll failed; [LiveSnapshot.data] is from an earlier one. */
    STALE,

    /** Every poll so far failed. */
    UNAVAILABLE,
}

enum class LiveError { NETWORK, RATE_LIMITED, SERVER, MALFORMED }

/**
 * What a screen showing live data renders. An empty list with [LiveStatus.FRESH] means
 * "no buses right now", which is normal at night and must not look like an error.
 */
data class LiveSnapshot<out T>(
    val data: T?,
    val status: LiveStatus,
    val lastUpdated: Instant? = null,
    val lastError: LiveError? = null,
)
