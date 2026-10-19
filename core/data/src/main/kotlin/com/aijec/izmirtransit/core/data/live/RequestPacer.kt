package com.aijec.izmirtransit.core.data.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * App-wide queue for live API calls: request starts are spaced at least [minInterval]
 * apart, in FIFO order. The İztek server returns 429 for bursts of ~3/s; 1/s never failed
 * in testing. Must be a singleton so every screen shares the same budget.
 */
class RequestPacer(
    private val minInterval: Duration = 1.seconds,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var nextAllowed: ComparableTimeMark? = null

    suspend fun <T> paced(block: suspend () -> T): T {
        mutex.withLock {
            nextAllowed?.let { allowed ->
                val wait = allowed - timeSource.markNow()
                if (wait.isPositive()) delay(wait)
            }
            nextAllowed = timeSource.markNow() + minInterval
        }
        return block()
    }

    /** Holds every caller back for [duration], e.g. after a 429. */
    suspend fun penalize(duration: Duration) {
        mutex.withLock {
            val until = timeSource.markNow() + duration
            val current = nextAllowed
            if (current == null || until > current) nextAllowed = until
        }
    }
}
