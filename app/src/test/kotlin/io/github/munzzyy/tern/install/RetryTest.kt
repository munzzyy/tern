package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {
    private fun passing(n: Int) = PassingFailure(StepFailure(ProblemKind.NETWORK, "cut off $n"))

    @Test
    fun aDownloadThatFailsOnTheWayIsTriedThreeMoreTimesWithAPauseBetween() = runBlocking {
        var calls = 0
        var waits = 0
        val got = Downloader.retrying(Downloader.RETRIES, { waits++ }) {
            calls++
            if (calls < 4) throw passing(calls)
            "done"
        }
        assertEquals("done", got)
        assertEquals(4, calls)
        assertEquals(3, waits)
    }

    @Test
    fun afterTheLastTryItsFailureIsTheOneGiven() {
        var calls = 0
        val last = passing(4)
        val thrown = assertThrows(StepFailure::class.java) {
            runBlocking {
                Downloader.retrying<Unit>(Downloader.RETRIES, {}) {
                    calls++
                    throw if (calls == 4) last else passing(calls)
                }
            }
        }
        assertSame(last.failure, thrown)
        assertEquals(4, calls)
    }

    @Test
    fun aFailureThatWillNotPassIsNotTriedAgain() {
        var calls = 0
        assertThrows(StepFailure::class.java) {
            runBlocking {
                Downloader.retrying<Unit>(Downloader.RETRIES, {}) {
                    calls++
                    throw StepFailure(ProblemKind.NOT_FOUND, "gone")
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun aServerThatAsksForLongerThanTheUsualPauseIsGivenItsWait() = runBlocking {
        val waits = ArrayList<Long>()
        var calls = 0
        val got = Downloader.retrying(Downloader.RETRIES, { waits += it }) {
            calls++
            when (calls) {
                1 -> throw PassingFailure(StepFailure(ProblemKind.RATE_LIMITED, "wait"), waitMs = 30_000)
                2 -> throw PassingFailure(StepFailure(ProblemKind.RATE_LIMITED, "wait"), waitMs = 1_000)
                3 -> throw passing(3)
                else -> "done"
            }
        }
        assertEquals("done", got)
        assertEquals(listOf(30_000L, Downloader.RETRY_WAIT_MS, Downloader.RETRY_WAIT_MS), waits)
    }

    @Test
    fun aRateLimitThatLiftsWithinAMinuteIsWaitedOut() {
        val now = 1_800_000_000_000L
        val limit = Downloader.limited(now + 20_000, now, "wait") as PassingFailure
        assertEquals(20_000L, limit.waitMs)
        assertEquals(ProblemKind.RATE_LIMITED, limit.failure.kind)
        assertEquals(now + 20_000, limit.failure.problem.retryAtMs)
        assertEquals(Downloader.MAX_RATE_WAIT_MS, (Downloader.limited(now + Downloader.MAX_RATE_WAIT_MS, now, "wait") as PassingFailure).waitMs)
        assertEquals(0L, (Downloader.limited(now - 5_000, now, "wait") as PassingFailure).waitMs)
    }

    @Test
    fun aLongerRateLimitEndsTheDownloadSayingUntilWhen() {
        val now = 1_800_000_000_000L
        val limit = Downloader.limited(now + 90_000, now, "not before 12:01")
        assertTrue(limit is StepFailure)
        limit as StepFailure
        assertEquals(ProblemKind.RATE_LIMITED, limit.kind)
        assertEquals("not before 12:01", limit.problem.message)
        assertEquals(now + 90_000, limit.problem.retryAtMs)
    }

    @Test
    fun aShortRateLimitIsTriedAgainOnceItLiftsAndALongOneIsNot() = runBlocking {
        val now = 1_800_000_000_000L
        val waits = ArrayList<Long>()
        var calls = 0
        val got = Downloader.retrying(Downloader.RETRIES, { waits += it }) {
            calls++
            if (calls == 1) throw Downloader.limited(now + 20_000, now, "wait")
            "done"
        }
        assertEquals("done", got)
        assertEquals(listOf(20_000L), waits)

        calls = 0
        val thrown = assertThrows(StepFailure::class.java) {
            runBlocking {
                Downloader.retrying<Unit>(Downloader.RETRIES, { waits += it }) {
                    calls++
                    throw Downloader.limited(now + 90_000, now, "wait")
                }
            }
        }
        assertEquals(ProblemKind.RATE_LIMITED, thrown.kind)
        assertEquals(1, calls)
        assertEquals(listOf(20_000L), waits)
    }

    @Test
    fun onlyABusyOrStrugglingServerIsAskedAgain() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertTrue("$status", Downloader.passing(status))
        for (status in listOf(200, 206, 400, 401, 403, 404, 410, 416, 451)) assertFalse("$status", Downloader.passing(status))
    }
}
