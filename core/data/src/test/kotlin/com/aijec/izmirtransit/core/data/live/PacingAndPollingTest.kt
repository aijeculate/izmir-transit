package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.LiveError
import com.aijec.izmirtransit.core.model.LiveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PacingAndPollingTest {
    @Test
    fun `pacer spaces request starts one second apart`() = runTest {
        val pacer = RequestPacer(1.seconds, testScheduler.timeSource)
        val starts = mutableListOf<Long>()
        repeat(3) { launch { pacer.paced { starts += currentTime } } }
        advanceUntilIdle()
        assertEquals(listOf(0L, 1000L, 2000L), starts)
    }

    @Test
    fun `penalty after a 429 holds back the next request`() = runTest {
        val pacer = RequestPacer(1.seconds, testScheduler.timeSource)
        pacer.penalize(5.seconds)
        var start = -1L
        pacer.paced { start = currentTime }
        assertEquals(5000L, start)
    }

    @Test
    fun `polling keeps last data as stale on failure and backs off`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC)
        val results = ArrayDeque(
            listOf(
                FetchResult.Success(listOf(1)),
                FetchResult.Failure(LiveError.NETWORK, retryable = true),
                FetchResult.Failure(LiveError.RATE_LIMITED, retryable = true),
                FetchResult.Success(listOf(2)),
            ),
        )
        val fetchTimes = mutableListOf<Long>()
        val snapshots = pollLive(15.seconds, clock) {
            fetchTimes += currentTime
            results.removeFirst()
        }.take(5).toList()

        assertEquals(
            listOf(LiveStatus.LOADING, LiveStatus.FRESH, LiveStatus.STALE, LiveStatus.STALE, LiveStatus.FRESH),
            snapshots.map { it.status },
        )
        assertEquals(listOf(null, listOf(1), listOf(1), listOf(1), listOf(2)), snapshots.map { it.data })
        assertEquals(LiveError.RATE_LIMITED, snapshots[3].lastError)
        assertEquals(clock.instant(), snapshots[1].lastUpdated)
        // 15 s after success, then 2 s and 4 s of backoff.
        assertEquals(listOf(0L, 15_000L, 17_000L, 21_000L), fetchTimes)
    }

    @Test
    fun `a lone 429 is retried quietly, repeated ones turn stale`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC)
        val busy = FetchResult.Failure(LiveError.RATE_LIMITED, retryable = true)
        val results = ArrayDeque(listOf(FetchResult.Success(listOf(1)), busy, FetchResult.Success(listOf(2)), busy, busy, busy))
        val snapshots = pollLive(15.seconds, clock) { results.removeFirst() }.take(7).toList()

        assertEquals(
            listOf(LiveStatus.LOADING, LiveStatus.FRESH, LiveStatus.FRESH, LiveStatus.FRESH, LiveStatus.FRESH, LiveStatus.FRESH, LiveStatus.STALE),
            snapshots.map { it.status },
        )
        assertEquals(null, snapshots[2].lastError)
        assertEquals(LiveError.RATE_LIMITED, snapshots[6].lastError)
    }

    @Test
    fun `failure before any data is unavailable`() = runTest {
        val snapshots = pollLive(15.seconds, Clock.systemUTC()) {
            FetchResult.Failure(LiveError.SERVER, retryable = false)
        }.take(2).toList()
        assertEquals(LiveStatus.UNAVAILABLE, snapshots[1].status)
    }

    @Test
    fun `backoff doubles and caps at one minute`() {
        assertEquals(listOf(2, 4, 8, 16, 32, 60, 60), (1..7).map { backoff(it).inWholeSeconds.toInt() })
    }
}
