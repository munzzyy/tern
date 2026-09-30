package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.select.AssetPicker
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
    fun aRunsArtifactIsAFileTheAppCanBeInstalledFrom() {
        val http = FakeHttp()
            .resource(runsUrl, "forge/github_workflow_runs.json")
            .resource(artifactsUrl, "forge/github_artifacts.json")
        val release = (source.check(spec, context(http)) as CheckResult.Listing).listing.releases.single()
        assertTrue(release.assets.single().holdsApps)
        val picks = AssetPicker.rank(release.assets, DeviceProfile.ARM64_PHONE, AssetPolicy())
        assertEquals("app-debug.zip", picks.single().asset.name)
        val selection = ReleaseSelector.select(listOf(release), ReleasePolicy(includePrereleases = true), 0L) {
            AssetPicker.rank(it.assets, DeviceProfile.ARM64_PHONE, AssetPolicy()).isNotEmpty()
        }
        assertEquals("555", selection.candidate?.id)
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

    private fun artifacts(vararg urls: String): List<String> {
        val body = """{"artifacts": [""" + urls.joinToString(",") { """{"name": "app", "archive_download_url": "$it"}""" } + "]}"
        val http = FakeHttp().resource(runsUrl, "forge/github_workflow_runs.json").text(artifactsUrl, body)
        val result = try {
            source.check(spec, context(http))
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
            return emptyList()
        }
        return (result as CheckResult.Listing).listing.releases[0].assets.map { it.url }
    }

    @Test
    fun aFileIsOnlyTakenFromTheHostThatGetsTheToken() {
        val good = "https://api.github.com/repos/example/app/actions/artifacts/9/zip"
        assertEquals(
            listOf(good),
            artifacts(
                "https://files.example.org/repos/example/app/actions/artifacts/9/zip",
                "https://api.github.com.example.org/artifacts/9/zip",
                "https://api.github.com:8443/artifacts/9/zip",
                "https://api.github.com@files.example.org/artifacts/9/zip",
                good,
            ),
        )
    }

    @Test
    fun withNoFileOnThatHostThereIsNothingToInstall() {
        assertEquals(emptyList<String>(), artifacts("https://files.example.org/artifacts/9/zip"))
    }
}
