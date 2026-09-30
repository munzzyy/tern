package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.SourceSpec
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertTrue(SourceTypes.TENCENT in SourceTypes.REPUBLISHING)
        assertTrue(SourceTypes.THIRD_PARTY_STORES.containsAll(SourceTypes.REPUBLISHING))
        assertEquals(SourceTypes.TRACK_ONLY, registry.sources.filter { it.trackOnly }.map { it.type }.toSet())
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

    private fun refusedCheck(registry: SourceRegistry, spec: SourceSpec): SourceException {
        try {
            registry.check(spec, context(FakeHttp()))
        } catch (e: SourceException) {
            return e
        }
        fail("expected a refusal")
        throw AssertionError()
    }

    @Test
    fun sitesThatOfferModifiedAppsAreRefusedAndNeverReadAsPages() {
        val registry = SourceRegistry.standard()
        val http = FakeHttp()
        for (url in listOf("https://liteapks.com/x.html", "https://apk4free.net/x/", "https://www.rockmods.net/apps/x", "https://www.farsroid.com/x/", "https://dl.farsroid.com/ap/x.apk")) {
            assertEquals(url, SourceRegistry.Closed.Refused(Refusal.MODIFIED_APPS), registry.closed(url))
            assertNull(url, registry.match(url))
            assertNull(url, registry.detect(url, context(http)))
            assertNull(url, registry.readAs(url, SourceTypes.HTML, context(http)))
        }
        assertEquals(emptyList<Any>(), http.requests)
        val e = refusedCheck(registry, SourceSpec(SourceTypes.HTML, "https://liteapks.com/x.html"))
        assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        assertEquals("Tern does not read sites that offer modified apps", e.message)
    }

    @Test
    fun storesThatAnswerOnlyTheirOwnAppAreRefused() {
        val registry = SourceRegistry.standard()
        for (url in listOf("https://www.rustore.ru/catalog/app/org.example", "https://example.en.uptodown.com/android", "https://www.coolapk.com/apk/org.example")) {
            assertEquals(url, SourceRegistry.Closed.Refused(Refusal.IMPERSONATION), registry.closed(url))
            assertNull(url, registry.detect(url, context(FakeHttp())))
        }
        assertEquals("Tern cannot read this store without pretending to be its app", refusedCheck(registry, SourceSpec("rustore", "https://www.rustore.ru/catalog/app/org.example")).message)
        assertTrue("no type Tern reads is one it refuses", SourceTypes.ALL.none { Refusal.ofType(it) != null })
    }

    @Test
    fun withStoresOffAStoreAddressIsClosedAndNotReadAsAPage() {
        val registry = SourceRegistry.standard(storesAllowed = { false })
        val http = FakeHttp()
        val page = "https://apkpure.com/example-app/org.example.app"
        assertEquals(SourceRegistry.Closed.StoresOff(SourceTypes.APKPURE), registry.closed(page))
        assertNull(registry.match(page))
        assertNull(registry.detect(page, context(http)))
        assertNull(registry.readAs(page, SourceTypes.HTML, context(http)))
        assertNull(registry.readAs("https://example.org/app", SourceTypes.APKPURE, context(http)))
        assertEquals(SourceRegistry.Closed.StoresOff(SourceTypes.APKPURE), registry.closed("https://d.apkpure.com/b/APK/org.example.app"))
        assertEquals(SourceRegistry.Closed.StoresOff(SourceTypes.HUAWEI), registry.closed("appgallery.huawei.com/#/app/C100000000"))
        assertEquals(emptyList<Any>(), http.requests)

        assertNull("the developers' own channels stay open", registry.closed("https://examplelabs.itch.io/example-quest"))
        assertEquals(SourceTypes.ITCHIO, registry.match("https://examplelabs.itch.io/example-quest")?.type)
        assertEquals(SourceTypes.GITHUB, registry.match("https://github.com/owner/repo")?.type)
    }

    @Test
    fun withStoresOffAStoreAppIsPausedAndAsksForNothing() {
        var allowed = false
        val registry = SourceRegistry.standard(storesAllowed = { allowed })
        val spec = SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/example-app/org.example.app", mapOf(SourceOptions.PACKAGE to "org.example.app"))
        val asPage = SourceSpec(SourceTypes.HTML, "https://apkpure.com/example-app/org.example.app")
        assertTrue(registry.paused(spec))
        assertTrue(registry.paused(asPage))
        assertFalse(registry.paused(SourceSpec(SourceTypes.GITHUB, "https://github.com/owner/repo")))
        val http = FakeHttp()
        for (paused in listOf(spec, asPage)) {
            try {
                registry.check(paused, context(http))
                fail("expected a refusal")
            } catch (e: SourceException) {
                assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
            }
            try {
                registry.resolve(paused, Asset("a.apk", "https://d.apkpure.com/b/APK/org.example.app?versionCode=1"), context(http))
                fail("expected a refusal")
            } catch (e: SourceException) {
                assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
            }
        }
        assertEquals(emptyList<Any>(), http.requests)

        allowed = true
        assertFalse(registry.paused(spec))
        assertEquals(SourceTypes.APKPURE, registry.match(spec.url)?.type)
    }

    @Test
    fun withStoresOffSearchLeavesThemOut() {
        var allowed = false
        val registry = SourceRegistry.standard(storesAllowed = { allowed })
        assertTrue(registry.searchable.none { (it as Source).type in SourceTypes.THIRD_PARTY_STORES })
        allowed = true
        assertTrue(registry.searchable.any { (it as Source).type in SourceTypes.THIRD_PARTY_STORES })
    }
}
