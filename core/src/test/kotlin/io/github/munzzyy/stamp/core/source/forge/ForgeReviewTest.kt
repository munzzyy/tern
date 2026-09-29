package io.github.munzzyy.stamp.core.source.forge

import io.github.munzzyy.stamp.core.model.AssetKind
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.PoliteHttp
import io.github.munzzyy.stamp.core.net.RateLimitedException
import io.github.munzzyy.stamp.core.net.RateLimiter
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.testing.FakeHttp
import io.github.munzzyy.stamp.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeReviewTest {
    private val github = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app")
    private val feedUrl = "https://github.com/example/app/releases.atom"
    private val apiUrl = "https://api.github.com/repos/example/app/releases?per_page=30"

    @Test
    fun theLastAllowedResponseIsDeliveredAndTheNextRequestIsHeld() {
        val fake = FakeHttp().on("https://api.example.com/a") {
            HttpResponse.of(200, "payload", Headers.of("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "100"), it.url)
        }
        val http = PoliteHttp(fake, RateLimiter { 0L }, "test")
        assertEquals("payload", http.execute(HttpRequest("https://api.example.com/a")).text())
        val held = assertThrows(RateLimitedException::class.java) { http.execute(HttpRequest("https://api.example.com/a")) }
        assertEquals(100_000L, held.retryAtMs)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun compressionIsLeftToTheTransport() {
        val fake = FakeHttp().text("https://example.com/", "ok")
        PoliteHttp(fake, RateLimiter { 0L }, "test").execute(HttpRequest("https://example.com/")).close()
        assertFalse(fake.requests.single().headers.keys.any { it.equals("Accept-Encoding", ignoreCase = true) })
    }

    @Test
    fun aSecondaryLimitWithOnlyRetryAfterBlocks() {
        val limiter = RateLimiter { 0L }
        for (status in listOf(403, 503)) {
            val until = limiter.record("h$status", HttpResponse.of(status, "", Headers.of("Retry-After" to "45")))
            assertEquals(45_000L, until)
            assertThrows(RateLimitedException::class.java) { limiter.check("h$status") }
        }
        assertNull(limiter.record("plain", HttpResponse.of(403, "")))
        assertNull(limiter.record("ok", HttpResponse.of(200, "", Headers.of("Retry-After" to "45"))))
    }

    @Test
    fun anApiRedirectByRepositoryNumberIsNotReadAsARename() {
        val http = FakeHttp()
            .resource(feedUrl, "forge/github_feed.atom")
            .on(apiUrl) { HttpResponse.of(200, Fixtures.text("forge/github_releases.json"), url = "https://api.github.com/repositories/123456/releases?per_page=30") }
        val result = GitHubSource().check(github, CheckContext(http, InMemoryValidatorStore()))
        assertNull((result as CheckResult.Listing).listing.movedTo)
    }

    @Test
    fun aChangedFeedAnsweredWith304IsNotAskedAgain() {
        val validators = InMemoryValidatorStore()
        val first = FakeHttp().resource(feedUrl, "forge/github_feed.atom").resource(apiUrl, "forge/github_releases.json")
        GitHubSource().check(github, CheckContext(first, validators))

        val second = FakeHttp()
            .resource(feedUrl, "forge/github_feed_changed.atom")
            .on(apiUrl) { HttpResponse.of(304, "", url = apiUrl) }
        assertEquals(CheckResult.Unchanged, GitHubSource().check(github, CheckContext(second, validators)))

        val third = FakeHttp().resource(feedUrl, "forge/github_feed_changed.atom")
        assertEquals(CheckResult.Unchanged, GitHubSource().check(github, CheckContext(third, validators)))
        assertTrue(third.requestsTo(apiUrl).isEmpty())
    }

    @Test
    fun aGitLabLinkWithAFreeTextNameIsStillInstallable() {
        val url = "https://gitlab.com/api/v4/projects/group%2Fapp/releases?per_page=20"
        val body = """[{"tag_name":"v1.0.0","name":"One","description":"","released_at":"2026-02-01T00:00:00.000Z",
            "_links":{"self":"https://gitlab.com/group/app/-/releases/v1.0.0"},
            "assets":{"links":[{"name":"Android build","url":"https://gitlab.com/group/app/-/package_files/9/app-1.0.0.apk?x=1"}]}}]"""
        val listing = (GitLabSource().check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), CheckContext(FakeHttp().text(url, body), InMemoryValidatorStore())) as CheckResult.Listing).listing
        val asset = listing.releases.single().assets.single()
        assertEquals(AssetKind.APK, asset.kind)
        assertEquals("app-1.0.0.apk", asset.name)
        assertEquals("https://gitlab.com/group/app/-/releases/v1.0.0", listing.releases.single().pageUrl)
    }

    @Test
    fun readsTimestampsWithAnOffset() {
        val utc = Iso8601.parseMs("2026-02-01T10:00:00Z")!!
        assertEquals(1769940000000L, utc)
        assertEquals(utc, Iso8601.parseMs("2026-02-01T12:00:00+02:00"))
        assertEquals(utc, Iso8601.parseMs("2026-02-01T04:30:00.123-05:30"))
        assertEquals(utc, Iso8601.parseMs("2026-02-01T10:00:00+00:00"))
        for (bad in listOf("2026-02-01", "2026-02-01T10:00:00", "2026-02-01T10:00:00+25:00", "2026-13-01T10:00:00Z", "yesterday", "")) {
            assertNull(bad, Iso8601.parseMs(bad))
        }
    }

    @Test
    fun dotsAloneAreNotARepository() {
        assertNull(GitHubSource().match("https://github.com/../.."))
        assertNull(GitHubSource().match("https://github.com/example/.."))
        assertNull(ForgejoSource().match("https://codeberg.org/./app"))
        assertFalse(RepoNames.isValid("."))
        assertTrue(RepoNames.isValid(".github"))
    }

    @Test
    fun upgradingHttpDropsItsDefaultPort() {
        assertEquals("https://example.com/x", Urls.normalize("http://example.com:80/x"))
        assertEquals("https://example.com:8443/x", Urls.normalize("http://example.com:8443/x"))
        assertEquals("https://example.com:80/x", Urls.normalize("https://example.com:80/x"))
    }
}
