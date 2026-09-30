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
    fun onlyABusyOrStrugglingServerIsAskedAgain() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertTrue("$status", Downloader.passing(status))
        for (status in listOf(200, 206, 400, 401, 403, 404, 410, 416, 451)) assertFalse("$status", Downloader.passing(status))
    }
}
