package io.github.munzzyy.tern.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterTest {
    private var now = 1_700_000_000_000L
    private val limiter = RateLimiter { now }

    private fun response(status: Int, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse.of(status, "", Headers.of(headers))

    @Test
    fun githubHeadersBlockAtReset() {
        val resetSeconds = (now / 1000) + 30
        val blockedAt = limiter.record(
            "api.github.com",
            response(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to resetSeconds.toString())),
        )
        assertEquals(resetSeconds * 1000, blockedAt)
        try {
            limiter.check("api.github.com")
            org.junit.Assert.fail("expected RateLimitedException")
        } catch (e: RateLimitedException) {
            assertEquals(resetSeconds * 1000, e.retryAtMs)
        }
    }

    @Test
    fun gitlabHeadersBlockAtReset() {
        val resetSeconds = (now / 1000) + 45
        val blockedAt = limiter.record(
            "gitlab.com",
            response(200, mapOf("ratelimit-remaining" to "0", "ratelimit-reset" to resetSeconds.toString())),
        )
        assertEquals(resetSeconds * 1000, blockedAt)
    }

    @Test
    fun retryAfterSecondsWins() {
        val blockedAt = limiter.record("example.com", response(429, mapOf("Retry-After" to "10")))
        assertEquals(now + 10_000, blockedAt)
    }

    @Test
    fun retryAfterHttpDateIsParsed() {
        val blockedAt = limiter.record("example.com", response(429, mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertTrue(blockedAt != null && blockedAt > 0)
    }

    @Test
    fun defaultBlockIsSixtySecondsWhenNothingNamed() {
        val blockedAt = limiter.record("example.com", response(429))
        assertEquals(now + 60_000, blockedAt)
    }

    @Test
    fun blockIsCappedAtSixHours() {
        val farFuture = (now / 1000) + 100_000
        val blockedAt = limiter.record(
            "example.com",
            response(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to farFuture.toString())),
        )
        assertEquals(now + 6 * 60 * 60 * 1000L, blockedAt)
    }

    @Test
    fun successWithZeroRemainingStillBlocks() {
        val resetSeconds = (now / 1000) + 5
        val blockedAt = limiter.record(
            "api.github.com",
            response(200, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to resetSeconds.toString())),
        )
        assertEquals(resetSeconds * 1000, blockedAt)
    }

    @Test
    fun healthyResponseDoesNotBlock() {
        val blockedAt = limiter.record(
            "api.github.com",
            response(200, mapOf("x-ratelimit-remaining" to "10", "x-ratelimit-reset" to ((now / 1000) + 30).toString())),
        )
        org.junit.Assert.assertNull(blockedAt)
        limiter.check("api.github.com")
    }

    @Test
    fun hostsAreIndependent() {
        limiter.record("a.example.com", response(429))
        try {
            limiter.check("a.example.com")
            org.junit.Assert.fail("expected block")
        } catch (_: RateLimitedException) {
        }
        limiter.check("b.example.com")
    }

    @Test
    fun blockExpiresAfterTimePasses() {
        limiter.record("example.com", response(429, mapOf("Retry-After" to "10")))
        now += 11_000
        limiter.check("example.com")
    }

    @Test
    fun threadSafetySmokeTest() {
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(200)
        val failures = AtomicInteger(0)
        repeat(200) { i ->
            executor.submit {
                try {
                    val host = "host-${i % 5}.example.com"
                    limiter.record(host, response(429, mapOf("Retry-After" to "1")))
                    limiter.check(host)
                } catch (_: RateLimitedException) {
                } catch (e: Exception) {
                    failures.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, failures.get())
    }

    @Test
    fun aWrongClockOnThePhoneDoesNotStretchTheWait() {
        val phoneNow = 1_000_000_000_000L
        val serverNow = phoneNow + 2 * 60 * 60 * 1000L
        val reset = (serverNow / 1000) + 90
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(serverNow).atZone(java.time.ZoneOffset.UTC),
        )
        val limiter = RateLimiter { phoneNow }
        val until = limiter.record(
            "api.example.com",
            response(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to reset.toString(), "Date" to date)),
        )
        assertEquals(phoneNow + 90_000L, until!! / 1000 * 1000)
    }
}
