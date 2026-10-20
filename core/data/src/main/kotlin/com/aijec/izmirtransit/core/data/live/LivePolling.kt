package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.LiveError
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Polls [fetch] while the flow is collected and stops when collection stops, so
 * collecting with `repeatOnLifecycle(STARTED)` gives foreground-only polling for free.
 *
 * On failure the last good data is kept and marked [LiveStatus.STALE]; retryable errors
 * back off exponentially (2 s, 4 s, … up to 1 min), non-retryable ones retry after 1 min.
 */
fun <T> pollLive(
    interval: Duration,
    clock: Clock,
    fetch: suspend () -> FetchResult<T>,
): Flow<LiveSnapshot<T>> = flow {
    var snapshot = LiveSnapshot<T>(data = null, status = LiveStatus.LOADING)
    emit(snapshot)
    var failures = 0
    var rateLimits = 0
    while (true) {
        val wait = when (val result = fetch()) {
            is FetchResult.Success -> {
                failures = 0
                rateLimits = 0
                snapshot = LiveSnapshot(result.value, LiveStatus.FRESH, lastUpdated = clock.instant())
                interval
            }
            is FetchResult.Failure -> {
                failures++
                rateLimits = if (result.error == LiveError.RATE_LIMITED) rateLimits + 1 else 0
                // Occasional 429s are normal even at our 1 req/s pace (22 in 950 by day), so
                // while the data is fresh a short run of them is retried quietly; only a longer
                // run (or any other error) marks it stale.
                val quiet = result.error == LiveError.RATE_LIMITED &&
                    rateLimits < QUIET_RATE_LIMITS &&
                    snapshot.status == LiveStatus.FRESH
                if (!quiet) {
                    snapshot = snapshot.copy(
                        status = if (snapshot.data == null) LiveStatus.UNAVAILABLE else LiveStatus.STALE,
                        lastError = result.error,
                    )
                }
                if (result.retryable) backoff(failures) else MAX_BACKOFF
            }
        }
        emit(snapshot)
        delay(wait)
    }
}

private val BASE_BACKOFF = 2.seconds

/** Consecutive 429s tolerated before the snapshot turns stale. */
private const val QUIET_RATE_LIMITS = 3
private val MAX_BACKOFF = 1.minutes

internal fun backoff(failures: Int): Duration {
    val factor = 1L shl min(failures - 1, 10).coerceAtLeast(0)
    return (BASE_BACKOFF * factor.toDouble()).coerceAtMost(MAX_BACKOFF)
}
