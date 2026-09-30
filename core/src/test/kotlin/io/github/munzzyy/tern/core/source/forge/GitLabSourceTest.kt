package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLabSourceTest {
    private val source = GitLabSource()
    private val releasesUrl = "https://gitlab.com/api/v4/projects/group%2Fapp/releases?per_page=20"

    private fun context(http: FakeHttp, host: String = "gitlab.com", token: String? = null) = CheckContext(
        http = http,
        validators = InMemoryValidatorStore(),
        tokens = if (token != null) TokenProvider { h -> if (h == host) token else null } else TokenProvider.NONE,
    )

    @Test
    fun matchesGitlabUrlsWithSubgroupsAndTagSuffix() {
        assertEquals(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), source.match("gitlab.com/group/app"))
        assertEquals(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), source.match("https://gitlab.com/group/app.git"))
        assertEquals(
            SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/sub/app"),
            source.match("https://gitlab.com/group/sub/app/-/tree/main"),
        )
        assertNull(source.match("https://github.com/group/app"))
    }

    @Test
    fun aGitlabOnItsOwnPortKeepsThePortInItsAddress() {
        val http = FakeHttp().resource("https://git.example.org:8443/api/v4/projects/group%2Fapp", "forge/gitlab_project.json")
        val spec = source.probe("https://git.example.org:8443/group/app", context(http))
        assertEquals(SourceSpec(SourceTypes.GITLAB, "https://git.example.org:8443/group/app"), spec)
    }

    @Test
    fun probeRecognisesSelfHostedGitlabByApiShape() {
        val projectUrl = "https://git.example.org/api/v4/projects/group%2Fapp"
        val http = FakeHttp().resource(projectUrl, "forge/gitlab_project.json")
        val spec = source.probe("https://git.example.org/group/app", context(http))
        assertEquals(SourceSpec(SourceTypes.GITLAB, "https://git.example.org/group/app"), spec)
    }

    @Test
    fun probeReturnsNullForGitlabComItself() {
        val http = FakeHttp()
        assertNull(source.probe("https://gitlab.com/group/app", context(http)))
    }

    @Test
    fun probeReturnsNullWhenNotJsonProject() {
        val projectUrl = "https://git.example.org/api/v4/projects/group%2Fapp"
        val http = FakeHttp().text(projectUrl, "not json")
        assertNull(source.probe("https://git.example.org/group/app", context(http)))
    }

    @Test
    fun happyPathMapsReleasesLinksAndUploads() {
        val http = FakeHttp().resource(releasesUrl, "forge/gitlab_releases.json")
        val result = source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(1, listing.releases.size)
        val release = listing.releases[0]
        assertEquals("v2.0.0", release.id)
        assertEquals(2, release.assets.size)
        assertTrue(release.assets.any { it.name == "app-release.apk" })
        assertTrue(release.assets.any { it.name == "notes.txt" && it.url == "https://gitlab.com/group/app/uploads/0123456789abcdef0123456789abcdef/notes.txt" })
    }

    @Test
    fun upcomingReleaseIsSkipped() {
        val http = FakeHttp().resource(releasesUrl, "forge/gitlab_releases.json")
        val result = source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases.none { it.id == "v2.1.0-upcoming" })
    }

    @Test
    fun unchangedOn304() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(304, "", url = releasesUrl) }
        val result = source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
        assertEquals(CheckResult.Unchanged, result)
    }

    @Test
    fun rateLimitMapsToSourceException() {
        val http = FakeHttp().on(releasesUrl) { throw RateLimitedException("gitlab.com", 42L) }
        try {
            source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.RATE_LIMITED, e.kind)
            assertEquals(42L, e.retryAtMs)
        }
    }

    @Test
    fun notFoundMapsToNotFound() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(404, "{}", url = releasesUrl) }
        try {
            source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun malformedJsonMapsToParse() {
        val http = FakeHttp().on(releasesUrl) { HttpResponse.of(200, "not json", url = releasesUrl) }
        try {
            source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun hostileAssetUrlIsDropped() {
        val hostile = """[{"tag_name":"v1.0.0","name":"v1.0.0","description":null,"created_at":"2026-01-01T00:00:00.000Z","upcoming_release":false,"assets":{"count":1,"links":[{"name":"bad.apk","url":"http://gitlab.com/group/app/bad.apk"}]}}]"""
        val http = FakeHttp().text(releasesUrl, hostile)
        val result = source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases[0].assets.isEmpty())
    }

    @Test
    fun theSourceOfEachReleaseIsOfferedAsGitLabNamesIt() {
        val body = """[{"tag_name":"v1.0.0","name":"v1.0.0","description":null,"created_at":"2026-01-01T00:00:00.000Z","upcoming_release":false,
            "assets":{"count":3,"links":[],"sources":[
            {"format":"zip","url":"https://gitlab.com/group/app/-/archive/v1.0.0/app-v1.0.0.zip"},
            {"format":"tar.gz","url":"https://gitlab.com/group/app/-/archive/v1.0.0/app-v1.0.0.tar.gz"},
            {"format":"tar","url":"https://elsewhere.example/app-v1.0.0.tar"}]}}]"""
        val http = FakeHttp().text(releasesUrl, body)
        val release = (source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http)) as CheckResult.Listing).listing.releases.single()
        assertTrue(release.assets.isEmpty())
        assertEquals(listOf("app-v1.0.0.zip", "app-v1.0.0.tar.gz"), release.sourceArchives.map { it.name })
    }

    @Test
    fun tokenIsSentOnlyToExactHost() {
        val http = FakeHttp().resource(releasesUrl, "forge/gitlab_releases.json")
        source.check(SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app"), context(http, token = "secret"))
        assertEquals("Bearer secret", http.requestsTo(releasesUrl).single().authorization)
    }
}
