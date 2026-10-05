package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedFailureTest {
    private val notFound = Problem(ProblemKind.NOT_FOUND, "There is nothing at this address.")

    @Test
    fun aCheckThatFailsAsTheOneBeforeItAddsNothingToTheLog() {
        assertFalse(Checks.logsFailure(notFound, notFound.copy()))
        val limited = Problem(ProblemKind.RATE_LIMITED, "The source asked Tern to wait.", 1_000)
        assertFalse(Checks.logsFailure(limited, limited.copy(retryAtMs = 2_000)))
    }

    @Test
    fun aFirstFailureOrAChangedOneIsLogged() {
        assertTrue(Checks.logsFailure(null, notFound))
        assertTrue(Checks.logsFailure(notFound, notFound.copy(message = "The server did not answer.")))
        assertTrue(Checks.logsFailure(notFound, notFound.copy(kind = ProblemKind.PARSE)))
    }
}
