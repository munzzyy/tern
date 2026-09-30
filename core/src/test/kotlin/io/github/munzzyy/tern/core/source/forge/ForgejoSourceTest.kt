package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.model.SourceSpec
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ForgejoSourceTest {
    private val source = ForgejoSource()
    private val releasesUrl = "https://codeberg.org/api/v1/repos/example/app/releases?limit=20"

    private fun context(http: FakeHttp, host: String = "codeberg.org", token: String? = null) = CheckContext(
        http = http,
        validators = InMemoryValidatorStore(),
        tokens = if (token != null) TokenProvider { h -> if (h == host) token else null } else TokenProvider.NONE,
    )

    @Test
    fun matchesCodebergUrls() {
        assertEquals(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), source.match("codeberg.org/example/app"))
        assertEquals(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), source.match("https://codeberg.org/example/app.git"))
        assertNull(source.match("https://gitea.example.org/example/app"))
    }

    @Test
    fun aForgeOnItsOwnPortIsAskedOnThatPort() {
        val http = FakeHttp()
            .resource("https://git.example.org:8443/api/v1/version", "forge/forgejo_version.json")
            .resource("https://git.example.org:8443/api/v1/repos/example/app/releases?limit=20", "forge/forgejo_releases.json")
        val spec = source.probe("https://git.example.org:8443/example/app", context(http, host = "git.example.org"))
        assertEquals(SourceSpec(SourceTypes.FORGEJO, "https://git.example.org:8443/example/app"), spec)
        val listing = (source.check(spec!!, context(http, host = "git.example.org")) as CheckResult.Listing).listing
        assertTrue(listing.releases.isNotEmpty())
        assertTrue(listing.releases.flatMap { it.assets }.isNotEmpty())
    }

    @Test
    fun probeRecognisesForgejoInstanceByVersionEndpoint() {
        val versionUrl = "https://git.example.org/api/v1/version"
        val http = FakeHttp().resource(versionUrl, "forge/forgejo_version.json")
        val spec = source.probe("https://git.example.org/example/app", context(http, host = "git.example.org"))
        assertEquals(SourceSpec(SourceTypes.FORGEJO, "https://git.example.org/example/app"), spec)
    }

    @Test
    fun probeReturnsNullForCodebergItself() {
        assertNull(source.probe("https://codeberg.org/example/app", context(FakeHttp())))
    }

    @Test
    fun probeReturnsNullWhenVersionFieldMissing() {
        val versionUrl = "https://git.example.org/api/v1/version"
        val http = FakeHttp().text(versionUrl, """{"other":"thing"}""")
        assertNull(source.probe("https://git.example.org/example/app", context(http, host = "git.example.org")))
    }

    @Test
    fun happyPathMapsReleases() {
        val http = FakeHttp().resource(releasesUrl, "forge/forgejo_releases.json")
        val result = source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(1, listing.releases.size)
        assertEquals("v3.0.0", listing.releases[0].id)
        assertEquals(1, listing.releases[0].assets.size)
    }

    @Test
    fun draftsAreSkipped() {
        val http = FakeHttp().resource(releasesUrl, "forge/forgejo_releases.json")
        val result = source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases.none { it.id == "v3.1.0-draft" })
    }

    @Test
    fun unchangedOn304() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(304, "", url = releasesUrl) }
        val result = source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
        assertEquals(CheckResult.Unchanged, result)
    }

    @Test
    fun rateLimitMapsToSourceException() {
        val http = FakeHttp().on(releasesUrl) { throw RateLimitedException("codeberg.org", 7L) }
        try {
            source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.RATE_LIMITED, e.kind)
            assertEquals(7L, e.retryAtMs)
        }
    }

    @Test
    fun notFoundMapsToNotFound() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(404, "{}", url = releasesUrl) }
        try {
            source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun malformedJsonMapsToParse() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(200, "not json", url = releasesUrl) }
        try {
            source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun hostileAssetUrlIsDropped() {
        val hostile = """[{"tag_name":"v1.0.0","name":"v1.0.0","body":"x","draft":false,"prerelease":false,"published_at":"2026-01-01T00:00:00Z","html_url":"https://codeberg.org/example/app/releases/tag/v1.0.0","assets":[{"name":"bad.apk","browser_download_url":"http://codeberg.org/example/app/bad.apk"}]}]"""
        val http = FakeHttp().text(releasesUrl, hostile)
        val result = source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases[0].assets.isEmpty())
    }

    @Test
    fun tokenIsSentOnlyToExactHost() {
        val http = FakeHttp().resource(releasesUrl, "forge/forgejo_releases.json")
        source.check(SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app"), context(http, token = "secret"))
        assertEquals("token secret", http.requestsTo(releasesUrl).single().authorization)
    }

    private val latestUrl = "https://codeberg.org/api/v1/repos/example/app/releases/latest"

    private fun spec(vararg options: Pair<String, String>) = SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app", options.toMap())

    private fun listing(http: FakeHttp, spec: SourceSpec) = (source.check(spec, context(http, token = "secret")) as CheckResult.Listing).listing

    @Test
    fun theReleaseTheForgeMarksAsLatestIsMarkedAndAddedWhenMissing() {
        val marked = FakeHttp().resource(releasesUrl, "forge/forgejo_file_dates.json")
            .text(latestUrl, """{"tag_name": "v2.0.0", "name": "v2.0.0", "draft": false, "prerelease": false, "assets": []}""")
        assertEquals(listOf("v3.0.0" to false, "v2.0.0" to true), listing(marked, spec(SourceOptions.VERIFY_LATEST to "true")).releases.map { it.id to it.latest })
        assertEquals("token secret", marked.requestsTo(latestUrl).single().authorization)

        val missing = FakeHttp().resource(releasesUrl, "forge/forgejo_file_dates.json")
            .text(latestUrl, """{"tag_name": "v1.0.0", "name": "v1.0.0", "draft": false, "prerelease": false, "assets": []}""")
        val releases = listing(missing, spec(SourceOptions.VERIFY_LATEST to "true")).releases
        assertEquals(listOf("v1.0.0", "v3.0.0", "v2.0.0"), releases.map { it.id })
        assertTrue(releases[0].latest)
    }

    @Test
    fun withNoReleaseMarkedLatestOrWithoutTheOptionNoneIsMarked() {
        val none = FakeHttp().resource(releasesUrl, "forge/forgejo_file_dates.json").text(latestUrl, "{}", status = 404)
        assertTrue(listing(none, spec(SourceOptions.VERIFY_LATEST to "true")).releases.none { it.latest })
        val plain = FakeHttp().resource(releasesUrl, "forge/forgejo_file_dates.json")
        assertTrue(listing(plain, spec()).releases.none { it.latest })
        assertTrue(plain.requestsTo(latestUrl).isEmpty())
    }

    @Test
    fun aReleaseCanBeDatedByItsNewestFile() {
        val http = FakeHttp().resource(releasesUrl, "forge/forgejo_file_dates.json")
        val dated = listing(http, spec(SourceOptions.ASSET_DATE to "true")).releases
        assertEquals(Instant.parse("2026-04-03T08:30:00Z").toEpochMilli(), dated[0].publishedAtMs)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), dated[1].publishedAtMs)
        assertEquals(Instant.parse("2026-04-01T00:00:00Z").toEpochMilli(), listing(http, spec()).releases[0].publishedAtMs)
    }
}
