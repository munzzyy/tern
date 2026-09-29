package io.github.munzzyy.stamp.core.source.forge

import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.RateLimitedException
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.source.TokenProvider
import io.github.munzzyy.stamp.core.testing.FakeHttp
import io.github.munzzyy.stamp.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSourceTest {
    private val source = GitHubSource()
    private val feedUrl = "https://github.com/example/app/releases.atom"
    private val apiUrl = "https://api.github.com/repos/example/app/releases?per_page=30"

    private fun context(http: FakeHttp, token: String? = null) = CheckContext(
        http = http,
        validators = InMemoryValidatorStore(),
        tokens = if (token != null) TokenProvider { host -> if (host == "api.github.com") token else null } else TokenProvider.NONE,
    )

    @Test
    fun matchesGithubUrlsWithVariants() {
        assertEquals(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), source.match("github.com/example/app"))
        assertEquals(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), source.match("https://github.com/example/app.git"))
        assertEquals(
            SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"),
            source.match("https://github.com/example/app/releases/tag/v1.0.0"),
        )
        assertNull(source.match("https://gitlab.com/example/app"))
        assertNull(source.match("https://github.com/settings/profile"))
        assertNull(source.match("https://github.com/example"))
    }

    @Test
    fun happyPathWithoutTokenUsesFeedThenApi() {
        val http = FakeHttp()
            .resource(feedUrl, "forge/github_feed.atom")
            .resource(apiUrl, "forge/github_releases.json")
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("app", listing.name)
        assertEquals("example", listing.author)
        assertEquals(2, listing.releases.size)
        val newest = listing.releases[0]
        assertEquals("v1.2.0", newest.id)
        assertEquals(1, newest.assets.size)
        assertEquals("app-arm64-v8a.apk", newest.assets[0].name)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd", newest.assets[0].sha256)
        assertEquals(1, http.requestsTo(apiUrl).size)
    }

    @Test
    fun unchangedOn304FromApiWhenTokenPresent() {
        val http = FakeHttp().on(apiUrl) { HttpResponse.of(304, "", url = apiUrl) }
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
        assertEquals(CheckResult.Unchanged, result)
    }

    @Test
    fun unchangedOnIdenticalFeedNeverCallsApi() {
        val http = FakeHttp()
            .resource(feedUrl, "forge/github_feed.atom")
            .resource(apiUrl, "forge/github_releases.json")
        val ctx = context(http)
        val spec = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app")
        source.check(spec, ctx)
        val second = source.check(spec, ctx)
        assertEquals(CheckResult.Unchanged, second)
        assertEquals(1, http.requestsTo(apiUrl).size)
    }

    @Test
    fun changedFeedTriggersExactlyOneApiCall() {
        val http = FakeHttp()
            .resource(feedUrl, "forge/github_feed.atom")
            .resource(apiUrl, "forge/github_releases.json")
        val ctx = context(http)
        val spec = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app")
        source.check(spec, ctx)

        val http2 = FakeHttp()
            .resource(feedUrl, "forge/github_feed_changed.atom")
            .resource(apiUrl, "forge/github_releases.json")
        val ctx2 = CheckContext(http2, ctx.validators, ctx.tokens)
        val result = source.check(spec, ctx2)
        assertTrue(result is CheckResult.Listing)
        assertEquals(1, http2.requestsTo(apiUrl).size)
    }

    @Test
    fun feed304ShortCircuitsWithoutApiCall() {
        val http = FakeHttp().on(feedUrl) { HttpResponse.of(304, "", url = feedUrl) }
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http))
        assertEquals(CheckResult.Unchanged, result)
        assertTrue(http.requestsTo(apiUrl).isEmpty())
    }

    @Test
    fun rateLimitMapsToSourceExceptionWithRetryAt() {
        val http = FakeHttp().on(apiUrl) { throw RateLimitedException("api.github.com", 123456L) }
        try {
            source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.RATE_LIMITED, e.kind)
            assertEquals(123456L, e.retryAtMs)
        }
    }

    @Test
    fun notFoundMapsToNotFound() {
        val http = FakeHttp().on(apiUrl) { HttpResponse.of(404, "{}", url = apiUrl) }
        try {
            source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun unauthorizedMapsToAuth() {
        val http = FakeHttp().on(apiUrl) { HttpResponse.of(401, "{}", url = apiUrl) }
        try {
            source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
        }
    }

    @Test
    fun malformedJsonMapsToParse() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases_malformed.json")
        try {
            source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun draftsAreSkipped() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json")
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases.none { it.id == "v1.3.0-draft" })
    }

    @Test
    fun hostileAssetUrlsAreDropped() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json")
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
        val listing = (result as CheckResult.Listing).listing
        val names = listing.releases.flatMap { it.assets }.map { it.name }
        assertTrue("insecure.apk" !in names)
        assertTrue("elsewhere.apk" !in names)
    }

    @Test
    fun noReleasesMapsToNoReleases() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases_no_releases.json")
        try {
            source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    @Test
    fun movedRepoIsReported() {
        val movedApiUrl = "https://api.github.com/repos/example/app/releases?per_page=30"
        val body = Fixtures.text("forge/github_releases.json")
        val http = FakeHttp().on(movedApiUrl) {
            HttpResponse.of(200, body, url = "https://api.github.com/repos/newowner/newapp/releases?per_page=30")
        }
        val result = source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "tok"))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("https://github.com/newowner/newapp", listing.movedTo)
    }

    @Test
    fun tokenIsSentOnlyToApiHost() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json")
        source.check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http, token = "secret"))
        val request = http.requestsTo(apiUrl).single()
        assertEquals("Bearer secret", request.authorization)
    }
}
