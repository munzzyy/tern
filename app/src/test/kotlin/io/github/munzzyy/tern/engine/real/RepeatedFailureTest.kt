package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedFailureTest {
    private val notFound = Problem(ProblemKind.NOT_FOUND, "There is nothing at this address.")

    private fun limited(until: String, at: Long?) =
        Problem(ProblemKind.RATE_LIMITED, "The source asked Tern to wait. It will not be asked again before $until.", at)

    private fun unreachable(port: Int) = Problem(
        ProblemKind.NETWORK,
        "The source could not be reached: failed to connect to forge.test/192.0.2.7 (port 443) from /10.0.2.15 (port $port) after 15000ms",
    )

    @Test
    fun aCheckThatFailsAsTheOneBeforeItAddsNothingToTheLog() {
        assertFalse(Checks.logsFailure(notFound, notFound.copy()))
    }

    @Test
    fun aRateLimitOrALostNetworkIsLoggedOnceThoughItsWordsChange() {
        assertFalse(Checks.logsFailure(limited("13:05", 1_000), limited("14:05", 2_000)))
        // Without a reset from the source the words name the time of the check.
        assertFalse(Checks.logsFailure(limited("13:05", null), limited("13:20", null)))
        assertFalse(Checks.logsFailure(unreachable(40112), unreachable(40598)))
        assertTrue(Checks.logsFailure(unreachable(40112), limited("13:05", 1_000)))
    }

    @Test
    fun aFirstFailureOrAChangedOneIsLogged() {
        assertTrue(Checks.logsFailure(null, notFound))
        assertTrue(Checks.logsFailure(notFound, notFound.copy(message = "The server did not answer.")))
        assertTrue(Checks.logsFailure(notFound, notFound.copy(kind = ProblemKind.PARSE)))
    }
}
