package io.github.munzzyy.tern.core.net

import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliteHttpTest {
    @Test
    fun rejectsInsecureUrls() {
        val fake = FakeHttp()
        val http = PoliteHttp(fake, RateLimiter { 0L }, "tern-test/1.0")
        try {
            http.execute(HttpRequest("http://example.com"))
            org.junit.Assert.fail("expected InsecureUrlException")
        } catch (_: InsecureUrlException) {
        }
    }

    @Test
    fun addsUserAgentAndAcceptEncodingWhenAbsent() {
        val fake = FakeHttp().text("https://example.com", "ok")
        val http = PoliteHttp(fake, RateLimiter { 0L }, "tern-test/1.0")
        http.execute(HttpRequest("https://example.com")).close()
        val sent = fake.requests.single()
        assertEquals("tern-test/1.0", sent.headers["User-Agent"])
    }

    @Test
    fun keepsCallerSuppliedHeaders() {
        val fake = FakeHttp().text("https://example.com", "ok")
        val http = PoliteHttp(fake, RateLimiter { 0L }, "tern-test/1.0")
        http.execute(HttpRequest("https://example.com", headers = mapOf("User-Agent" to "custom", "Accept-Encoding" to "gzip"))).close()
        val sent = fake.requests.single()
        assertEquals("custom", sent.headers["User-Agent"])
        assertEquals("gzip", sent.headers["Accept-Encoding"])
    }

    @Test
    fun checksLimiterBeforeSending() {
        val fake = FakeHttp().text("https://blocked.example.com", "ok")
        var now = 1_000L
        val limiter = RateLimiter { now }
        limiter.record("blocked.example.com", HttpResponse.of(429))
        val http = PoliteHttp(fake, limiter, "tern-test/1.0")
        try {
            http.execute(HttpRequest("https://blocked.example.com"))
            org.junit.Assert.fail("expected RateLimitedException")
        } catch (e: RateLimitedException) {
            assertEquals("blocked.example.com", e.host)
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun throwsWhenResponseItselfIsRateLimited() {
        val fake = FakeHttp().on("https://example.com") {
            HttpResponse.of(429, "", Headers.of(mapOf("Retry-After" to "30")), "https://example.com")
        }
        val http = PoliteHttp(fake, RateLimiter { 0L }, "tern-test/1.0")
        try {
            http.execute(HttpRequest("https://example.com"))
            org.junit.Assert.fail("expected RateLimitedException")
        } catch (e: RateLimitedException) {
            assertEquals(30_000L, e.retryAtMs)
        }
    }

    @Test
    fun tokenNeverReachesAnyOtherHost() {
        val fake = FakeHttp()
            .text("https://api.github.com/x", "ok")
            .text("https://evil.example.com/y", "ok")
        val http = PoliteHttp(fake, RateLimiter { 0L }, "tern-test/1.0")
        http.execute(HttpRequest("https://api.github.com/x", authorization = "Bearer secret")).close()
        http.execute(HttpRequest("https://evil.example.com/y")).close()

        val toApi = fake.requestsTo("https://api.github.com/x").single()
        val toEvil = fake.requestsTo("https://evil.example.com/y").single()
        assertEquals("Bearer secret", toApi.authorization)
        assertNull(toEvil.authorization)
    }
}
