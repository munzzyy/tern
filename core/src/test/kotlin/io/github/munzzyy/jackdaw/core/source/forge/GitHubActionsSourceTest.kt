package io.github.munzzyy.jackdaw.core.source.forge

import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.net.InMemoryValidatorStore
import io.github.munzzyy.jackdaw.core.net.RateLimitedException
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.source.TokenProvider
import io.github.munzzyy.jackdaw.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsSourceTest {
    private val source = GitHubActionsSource()
    private val runsUrl = "https://api.github.com/repos/example/app/actions/workflows/build.yml/runs?status=success&per_page=5"
    private val artifactsUrl = "https://api.github.com/repos/example/app/actions/runs/555/artifacts"
    private val spec = SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app", mapOf(SourceOptions.WORKFLOW to "build.yml"))

    private fun context(http: FakeHttp, token: String? = "tok") = CheckContext(
        http = http,
        validators = InMemoryValidatorStore(),
        tokens = if (token != null) TokenProvider { host -> if (host == "api.github.com") token else null } else TokenProvider.NONE,
    )

    @Test
    fun matchAlwaysReturnsNull() {
        assertNull(source.match("https://github.com/example/app"))
        assertNull(source.match("anything"))
    }

    @Test
    fun requiresTokenForCiArtifacts() {
        val http = FakeHttp()
        try {
            source.check(spec, context(http, token = null))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.AUTH, e.kind)
            assertTrue(e.message!!.contains("token"))
        }
    }

    @Test
    fun requiresWorkflowOption() {
        val http = FakeHttp()
        val noWorkflow = SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app")
        try {
            source.check(noWorkflow, context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun happyPathMapsRunAndArtifacts() {
        val http = FakeHttp()
            .resource(runsUrl, "forge/github_workflow_runs.json")
            .resource(artifactsUrl, "forge/github_artifacts.json")
        val result = source.check(spec, context(http))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(1, listing.releases.size)
        val release = listing.releases[0]
        assertEquals("555", release.id)
        assertEquals("42-abcdef1", release.version)
        assertTrue(release.prerelease)
        assertEquals(1, release.assets.size)
        val asset = release.assets[0]
        assertEquals("app-debug.zip", asset.name)
        assertEquals(AssetKind.ARCHIVE, asset.kind)
        assertTrue(asset.needsAuth)
    }

    @Test
    fun expiredArtifactsAreExcluded() {
        val http = FakeHttp()
            .resource(runsUrl, "forge/github_workflow_runs.json")
            .resource(artifactsUrl, "forge/github_artifacts.json")
        val result = source.check(spec, context(http))
        val listing = (result as CheckResult.Listing).listing
        assertTrue(listing.releases[0].assets.none { it.name == "old-artifact.zip" })
    }

    @Test
    fun unchangedOn304ForRuns() {
        val http = FakeHttp().on(runsUrl) { HttpResponse.of(304, "", url = runsUrl) }
        val result = source.check(spec, context(http))
        assertEquals(CheckResult.Unchanged, result)
    }

    @Test
    fun rateLimitMapsToSourceException() {
        val http = FakeHttp().on(runsUrl) { throw RateLimitedException("api.github.com", 999L) }
        try {
            source.check(spec, context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.RATE_LIMITED, e.kind)
            assertEquals(999L, e.retryAtMs)
        }
    }

    @Test
    fun notFoundMapsToNotFound() {
        val http = FakeHttp().on(runsUrl) { HttpResponse.of(404, "{}", url = runsUrl) }
        try {
            source.check(spec, context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun malformedRunsJsonMapsToParse() {
        val http = FakeHttp().on(runsUrl) { HttpResponse.of(200, "{ not json", url = runsUrl) }
        try {
            source.check(spec, context(http))
            org.junit.Assert.fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    @Test
    fun branchOptionIsAppendedToRunsUrl() {
        val branchSpec = SourceSpec(
            SourceTypes.GITHUB_ACTIONS,
            "https://github.com/example/app",
            mapOf(SourceOptions.WORKFLOW to "build.yml", SourceOptions.BRANCH to "main"),
        )
        val branchUrl = "$runsUrl&branch=main"
        val http = FakeHttp()
            .resource(branchUrl, "forge/github_workflow_runs.json")
            .resource(artifactsUrl, "forge/github_artifacts.json")
        val result = source.check(branchSpec, context(http))
        assertTrue(result is CheckResult.Listing)
    }

    @Test
    fun tokenIsSentAsBearerToApiHostOnly() {
        val http = FakeHttp()
            .resource(runsUrl, "forge/github_workflow_runs.json")
            .resource(artifactsUrl, "forge/github_artifacts.json")
        source.check(spec, context(http, token = "secret"))
        assertEquals("Bearer secret", http.requestsTo(runsUrl).single().authorization)
        assertEquals("Bearer secret", http.requestsTo(artifactsUrl).single().authorization)
    }
}
