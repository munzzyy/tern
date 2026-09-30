package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.forge.ForgejoSource
import io.github.munzzyy.tern.core.source.forge.GitHubActionsSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.source.forge.GitLabSource
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {
    private val github = GitHubSource()
    private val githubActions = GitHubActionsSource()
    private val gitlab = GitLabSource()
    private val forgejo = ForgejoSource()
    private val registry = SourceRegistry(listOf(github, gitlab, forgejo, githubActions))

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    @Test
    fun getFindsByType() {
        assertSame(github, registry.get(SourceTypes.GITHUB))
        assertSame(gitlab, registry.get(SourceTypes.GITLAB))
        assertNull(registry.get("nope"))
    }

    @Test
    fun matchPicksFirstOwningSource() {
        val spec = registry.match("github.com/example/app")
        assertEquals(SourceTypes.GITHUB, spec?.type)
    }

    @Test
    fun theSourcesThatRepublishAreTheOnesTheListNames() {
        val registry = SourceRegistry.standard()
        assertEquals(SourceTypes.REPUBLISHING, registry.sources.filter { it.republishes }.map { it.type }.toSet())
        assertTrue(SourceTypes.REPUBLISHING.containsAll(SourceTypes.MODIFIED))
    }

    @Test
    fun aStorePageRoutedAfterAHashIsReadAsTyped() {
        val registry = SourceRegistry.standard()
        assertEquals(SourceTypes.HUAWEI, registry.match("appgallery.huawei.com/#/app/C100000000")?.type)
        assertEquals("https://appgallery.huawei.com/app/C100000000", registry.match("https://appgallery.huawei.com/#/app/C100000000")?.url)
        assertEquals(SourceTypes.VIVO, registry.match("https://h5.appstore.vivo.com.cn/#/details?appId=123")?.type)
        // Anywhere else the part after '#' means nothing, and the address is matched without it.
        assertEquals(SourceTypes.GITHUB, registry.match("https://github.com/owner/repo/#/readme")?.type)
    }

    @Test
    fun matchReturnsNullForUnrecognisedUrl() {
        assertNull(registry.match("not a url"))
        assertNull(registry.match("https://example.com/nothing"))
    }

    @Test
    fun detectFallsBackToProbeForSelfHostedGitlab() {
        val projectUrl = "https://git.example.org/api/v4/projects/group%2Fapp"
        val versionUrl = "https://git.example.org/api/v1/version"
        val http = FakeHttp()
            .on(versionUrl) { io.github.munzzyy.tern.core.net.HttpResponse.of(404, "", url = versionUrl) }
            .resource(projectUrl, "forge/gitlab_project.json")
        val spec = registry.detect("https://git.example.org/group/app", context(http))
        assertEquals(SourceTypes.GITLAB, spec?.type)
    }

    @Test
    fun detectSwallowsProbeIoException() {
        val http = FakeHttp()
        val spec = registry.detect("https://unreachable.example.org/group/app", context(http))
        assertNull(spec)
    }

    @Test
    fun theStandardOrderTriesABareDownloadAfterForgesAndBeforeThePageReader() {
        val standard = SourceRegistry.standard()
        val types = standard.sources.map { it.type }
        assertTrue(types.indexOf(SourceTypes.FORGEJO) < types.indexOf(SourceTypes.FDROID_REPO))
        assertTrue(types.indexOf(SourceTypes.FDROID_REPO) < types.indexOf(SourceTypes.DIRECT))
        assertEquals(SourceTypes.HTML, types.last())

        val quiet = FakeHttp()
        assertEquals(SourceTypes.GITHUB, standard.detect("https://github.com/example/app", context(quiet))?.type)
        assertTrue(quiet.requests.isEmpty())

        val bare = "https://example.org/dl/android/apk"
        val http = FakeHttp().on(bare) { HttpResponse.of(200, "", Headers.of("Content-Type" to "application/vnd.android.package-archive"), bare) }
        assertEquals(SourceTypes.DIRECT, standard.detect(bare, context(http))?.type)
        assertEquals(bare, http.requests.last().url)
        assertEquals("HEAD", http.requests.last().method)
        assertTrue(http.requests.dropLast(1).none { it.url == bare })
        assertTrue(http.requests.size > 1)
    }

    @Test
    fun aPageThatIsNotAFileIsLeftToThePageReader() {
        val page = "https://example.org/download"
        val http = FakeHttp().on(page) { HttpResponse.of(200, "", Headers.of("Content-Type" to "text/html"), page) }
        assertNull(SourceRegistry.standard().detect(page, context(http)))
    }

    @Test
    fun matchIsIndependentOfRegistrationOrder() {
        val reordered = SourceRegistry(listOf(gitlab, github))
        val spec = reordered.match("github.com/example/app")
        assertEquals(SourceTypes.GITHUB, spec?.type)
    }
}
