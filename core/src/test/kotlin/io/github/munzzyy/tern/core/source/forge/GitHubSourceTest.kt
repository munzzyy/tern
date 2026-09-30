package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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

    private val latestUrl = "https://api.github.com/repos/example/app/releases/latest"

    private fun spec(vararg options: Pair<String, String>) = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app", options.toMap())

    private fun listing(http: FakeHttp, spec: SourceSpec) = (source.check(spec, context(http, token = "tok")) as CheckResult.Listing).listing

    @Test
    fun theReleaseGitHubMarksAsLatestIsMarkedWhenAsked() {
        val http = FakeHttp()
            .resource(apiUrl, "forge/github_releases.json")
            .text(latestUrl, """{"tag_name": "v1.1.0", "name": "App 1.1.0", "draft": false, "prerelease": false, "assets": []}""")
        val releases = listing(http, spec(SourceOptions.VERIFY_LATEST to "true")).releases
        assertEquals(listOf("v1.2.0" to false, "v1.1.0" to true), releases.map { it.id to it.latest })
        assertEquals("Bearer tok", http.requestsTo(latestUrl).single().authorization)
    }

    @Test
    fun aLatestReleaseTheListingLacksIsAddedInFront() {
        val latest = """{"tag_name": "v0.9.0", "name": "Old", "draft": false, "prerelease": false, "published_at": "2025-01-01T00:00:00Z",
            "assets": [{"name": "app.apk", "browser_download_url": "https://github.com/example/app/releases/download/v0.9.0/app.apk"}]}"""
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json").text(latestUrl, latest)
        val releases = listing(http, spec(SourceOptions.VERIFY_LATEST to "true")).releases
        assertEquals(listOf("v0.9.0", "v1.2.0", "v1.1.0"), releases.map { it.id })
        assertTrue(releases[0].latest)
        assertEquals("app.apk", releases[0].assets.single().name)
    }

    @Test
    fun whenGitHubMarksNoReleaseAsLatestNoneIsMarked() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json").text(latestUrl, """{"message": "Not Found"}""", status = 404)
        assertTrue(listing(http, spec(SourceOptions.VERIFY_LATEST to "true")).releases.none { it.latest })
    }

    @Test
    fun theLatestReleaseIsOnlyAskedForWhenTheOptionIsSetAndTheListingChanged() {
        val plain = FakeHttp().resource(apiUrl, "forge/github_releases.json")
        assertTrue(listing(plain, spec()).releases.none { it.latest })
        assertTrue(plain.requestsTo(latestUrl).isEmpty())

        val unchanged = FakeHttp().on(apiUrl) { HttpResponse.of(304, "", url = apiUrl) }
        assertEquals(CheckResult.Unchanged, source.check(spec(SourceOptions.VERIFY_LATEST to "true"), context(unchanged, token = "tok")))
        assertTrue(unchanged.requestsTo(latestUrl).isEmpty())
    }

    @Test
    fun aFailedQuestionForTheLatestReleaseFailsTheCheck() {
        val http = FakeHttp().resource(apiUrl, "forge/github_releases.json").text(latestUrl, "oops", status = 502)
        try {
            listing(http, spec(SourceOptions.VERIFY_LATEST to "true"))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NETWORK, e.kind)
        }
    }

    @Test
    fun withATokenFilesComeThroughTheApiAsTheFileItself() {
        val body = """[{"tag_name": "v2.0", "name": "", "draft": false, "prerelease": false, "assets": [{"name": "app.apk",
            "url": "https://api.github.com/repos/example/app/releases/assets/42",
            "browser_download_url": "https://github.com/example/app/releases/download/v2.0/app.apk"}]}]"""
        val withToken = listing(FakeHttp().text(apiUrl, body), spec()).releases.single().assets.single()
        assertEquals("https://api.github.com/repos/example/app/releases/assets/42", withToken.url)
        assertTrue(withToken.needsAuth)
        val download = source.resolve(spec(), withToken, context(FakeHttp()))
        assertEquals("application/octet-stream", download.headers["Accept"])

        val anonymous = (source.check(spec(), context(FakeHttp().text(feedUrl, "<feed/>").text(apiUrl, body))) as CheckResult.Listing).listing
        val file = anonymous.releases.single().assets.single()
        assertEquals("https://github.com/example/app/releases/download/v2.0/app.apk", file.url)
        assertEquals(false, file.needsAuth)
        assertTrue(source.resolve(spec(), file, context(FakeHttp())).headers.isEmpty())
    }

    @Test
    fun theSourceOfEachReleaseIsOfferedToSaveAndNeverAsAFileToInstall() {
        val body = """[{"tag_name": "v2.0", "name": "", "draft": false, "prerelease": false, "assets": [],
            "tarball_url": "https://api.github.com/repos/example/app/tarball/v2.0",
            "zipball_url": "https://api.github.com/repos/example/app/zipball/v2.0"}]"""
        val release = listing(FakeHttp().text(apiUrl, body), spec()).releases.single()
        assertTrue(release.assets.isEmpty())
        assertEquals(listOf("app-v2.0.tar.gz", "app-v2.0.zip"), release.sourceArchives.map { it.name })
        assertEquals("https://api.github.com/repos/example/app/zipball/v2.0", release.sourceArchives[1].url)
        // Read with the token, so the token may go to the API for the archive of a private project, and nowhere else.
        assertTrue(release.sourceArchives.all { it.needsAuth })

        val anonymous = (source.check(spec(), context(FakeHttp().text(feedUrl, "<feed/>").text(apiUrl, body))) as CheckResult.Listing).listing
        assertTrue(anonymous.releases.single().sourceArchives.none { it.needsAuth })
    }

    @Test
    fun anArchiveAddressOffTheApiOfTheProjectsGitHubIsLeftOut() {
        val body = """[{"tag_name": "v2.0", "name": "", "draft": false, "prerelease": false, "assets": [],
            "tarball_url": "https://elsewhere.example/tarball/v2.0",
            "zipball_url": "http://api.github.com/repos/example/app/zipball/v2.0"}]"""
        assertTrue(listing(FakeHttp().text(apiUrl, body), spec()).releases.single().sourceArchives.isEmpty())
    }

    @Test
    fun aRefusedTokenIsTriedOnceWithout() {
        val http = FakeHttp().on(apiUrl) { request ->
            if (request.authorization != null) HttpResponse.of(401, """{"message": "Bad credentials"}""", url = apiUrl) else HttpResponse.of(200, Fixtures.text("forge/github_releases.json"), url = apiUrl)
        }
        val releases = listing(http, spec()).releases
        assertEquals(listOf("v1.2.0", "v1.1.0"), releases.map { it.id })
        assertEquals(listOf("Bearer tok", null), http.requestsTo(apiUrl).map { it.authorization })
        assertTrue(releases.flatMap { it.assets }.none { it.needsAuth })
    }

    @Test
    fun aProjectWithoutReleasesListsItsTagsForAnAppThatIsOnlyTracked() {
        val tagsUrl = "https://api.github.com/repos/example/app/tags?per_page=30"
        val http = FakeHttp().text(apiUrl, "[]").on(tagsUrl) { request ->
            if (request.headers["If-None-Match"] == "\"t1\"") HttpResponse.of(304, "", url = tagsUrl)
            else HttpResponse.of(200, """[{"name": "v3.1"}, {"name": "v3.0"}]""", Headers.of("ETag" to "\"t1\""), tagsUrl)
        }
        val tracked = io.github.munzzyy.tern.core.model.AppConfig(id = "a", source = spec(), name = "App", trackOnly = true)
        val validators = InMemoryValidatorStore()
        val ctx = CheckContext(http, validators, TokenProvider { if (it == "api.github.com") "tok" else null }, app = tracked)
        val releases = (source.check(spec(), ctx) as CheckResult.Listing).listing.releases
        assertEquals(listOf("v3.1", "v3.0"), releases.map { it.id })
        assertTrue(releases.all { it.assets.isEmpty() })
        assertEquals(CheckResult.Unchanged, source.check(spec(), ctx))
        try {
            listing(FakeHttp().text(apiUrl, "[]"), spec())
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    @Test
    fun aReleaseCanBeDatedByItsNewestFile() {
        val http = FakeHttp().resource(apiUrl, "forge/github_file_dates.json")
        val dated = listing(http, spec(SourceOptions.ASSET_DATE to "true")).releases
        assertEquals(Instant.parse("2026-03-05T10:00:00Z").toEpochMilli(), dated[0].publishedAtMs)
        assertEquals(Instant.parse("2025-06-01T00:00:00Z").toEpochMilli(), dated[1].publishedAtMs)
        val own = listing(http, spec()).releases
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), own[0].publishedAtMs)
    }
}
