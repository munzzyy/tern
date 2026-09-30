package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.Retries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetriesTest {
    private val now = 1_000_000_000L
    private val network = Problem(ProblemKind.NETWORK, "The connection was cut")
    private val notFound = Problem(ProblemKind.NOT_FOUND, "Gone")

    @Test
    fun aNetworkFailureIsTriedAgainLaterEachTime() {
        assertEquals(listOf("a") to 30_000L, Retries.plan(listOf("a" to network), 0, now))
        assertEquals(listOf("a") to 60_000L, Retries.plan(listOf("a" to network), 1, now))
        assertEquals(listOf("a") to 240_000L, Retries.plan(listOf("a" to network), 3, now))
        assertNull(Retries.plan(listOf("a" to network), Retries.MOST, now))
    }

    @Test
    fun aRateLimitIsWaitedOut() {
        val limited = Problem(ProblemKind.RATE_LIMITED, "Too many requests", retryAtMs = now + 10 * 60_000L)
        assertEquals(listOf("a", "b") to 10 * 60_000L, Retries.plan(listOf("a" to network, "b" to limited), 0, now))
        val forever = Problem(ProblemKind.RATE_LIMITED, "Too many requests", retryAtMs = now + 48 * 3_600_000L)
        assertEquals(listOf("b") to 6 * 3_600_000L, Retries.plan(listOf("b" to forever), 0, now))
    }

    @Test
    fun whatDoesNotPassByItselfWaitsForTheNextCheck() {
        assertNull(Retries.plan(listOf("a" to notFound), 0, now))
        assertEquals(listOf("b") to 30_000L, Retries.plan(listOf("a" to notFound, "b" to network), 0, now))
        assertNull(Retries.plan(emptyList(), 0, now))
    }
}
