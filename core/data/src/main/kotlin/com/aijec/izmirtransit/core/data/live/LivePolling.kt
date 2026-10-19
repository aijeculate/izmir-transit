package com.aijec.izmirtransit.core.data.live

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
    while (true) {
        val wait = when (val result = fetch()) {
            is FetchResult.Success -> {
                failures = 0
                snapshot = LiveSnapshot(result.value, LiveStatus.FRESH, lastUpdated = clock.instant())
                interval
            }
            is FetchResult.Failure -> {
                failures++
                snapshot = snapshot.copy(
                    status = if (snapshot.data == null) LiveStatus.UNAVAILABLE else LiveStatus.STALE,
                    lastError = result.error,
                )
                if (result.retryable) backoff(failures) else MAX_BACKOFF
            }
        }
        emit(snapshot)
        delay(wait)
    }
}

private val BASE_BACKOFF = 2.seconds
private val MAX_BACKOFF = 1.minutes

internal fun backoff(failures: Int): Duration {
    val factor = 1L shl min(failures - 1, 10).coerceAtLeast(0)
    return (BASE_BACKOFF * factor.toDouble()).coerceAtMost(MAX_BACKOFF)
}
