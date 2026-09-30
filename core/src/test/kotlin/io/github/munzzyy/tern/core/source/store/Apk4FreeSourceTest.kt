package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Apk4FreeSourceTest {
    private val source = Apk4FreeSource()
    private val page = "https://apk4free.net/example-notes/"
    private val downloads = "https://apk4free.net/example-notes/download/"
    private val spec = SourceSpec(source.type, page)
    private val files = "https://apps.apk4free.net/example-notes"

    private fun site(
        app: String = Fixtures.text("store/apk4free_app.html"),
        download: String = Fixtures.text("store/apk4free_download.html"),
    ): FakeHttp = FakeHttp().text(page, app).text(downloads, download)

    private fun listing(http: FakeHttp): SourceListing =
        (source.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceException {
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        } catch (e: SourceException) {
            return e
        }
        fail("expected a SourceException")
        throw AssertionError()
    }

    @Test
    fun matchesAnAppPageInItsCanonicalForm() {
        for (url in listOf(page, "https://www.apk4free.net/example-notes", "http://apk4free.net/example-notes/download/", "apk4free.net/example-notes/?ref=1")) {
            assertEquals(url, spec, source.match(url))
        }
    }

    @Test
    fun leavesOtherPagesAndHostsAlone() {
        for (url in listOf(
            "https://apk4free.net/",
            "https://apk4free.net/apps/productivity/",
            "https://apk4free.net/editors-choice/",
            "https://apps.apk4free.net/example-notes/Example-Notes-v2.5.1-Mod.apk",
            "https://apk4free.net.example.org/example-notes/",
            "https://example.org/example-notes/",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun groupsTheFilesOfTheDownloadPageByVersion() {
        val http = site()
        val listing = listing(http)
        assertEquals("Example Notes", listing.name)
        assertNull(listing.author)
        assertEquals(listOf("2.6.0-beta2", "2.5.1", "2.4.9"), listing.releases.map { it.version })

        val beta = listing.releases[0]
        assertTrue(beta.prerelease)
        assertEquals(listOf(Asset("Example-Notes-v2.6.0-beta2.apk", "$files/Example-Notes-v2.6.0-beta2.apk")), beta.assets)

        val current = listing.releases[1]
        assertEquals("2.5.1", current.id)
        assertFalse(current.prerelease)
        assertEquals("Example Notes v2.5.1 Premium APK", current.title)
        assertEquals(1773150072000L, current.publishedAtMs)
        assertEquals(page, current.pageUrl)
        assertEquals(
            listOf(
                Asset("Example-Notes-v2.5.1-Mod.apk", "$files/Example-Notes-v2.5.1-Mod.apk"),
                Asset("Example-Notes-v2.5.1-Mod-arm64-v8a.apk", "$files/Example-Notes-v2.5.1-Mod-arm64-v8a.apk"),
                Asset("Example-Notes-Final.apk", "https://apk4free.net/example-notes/Example-Notes-Final.apk"),
            ),
            current.assets,
        )

        val older = listing.releases[2]
        assertNull(older.publishedAtMs)
        assertEquals(listOf("$files/Example-Notes-v2.4.9-Mod.apk"), older.assets.map { it.url })
        assertEquals(listOf(page, downloads), http.requests.map { it.url })
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val urls = listing(site()).releases.flatMap { release -> release.assets.map { it.url } }
        assertTrue(urls.none { it.contains("example.org") })
        assertEquals(5, urls.size)
    }

    @Test
    fun withoutButtonsAnyLinkToAnAppCounts() {
        val plain = Fixtures.text("store/apk4free_download.html").replace("buttond downloadAPK dapk_b", "plain")
        val versions = listing(site(download = plain)).releases.map { it.version }
        assertEquals(listOf("2.6.0-beta2", "2.5.1", "2.4.9"), versions)
    }

    @Test
    fun theVersionComesFromTheTitleWhenThePageNamesNoneApart() {
        val app = Fixtures.text("store/apk4free_app.html").replace("<div class=\"version\">2.5.1</div>", "")
        val download = """<a class="buttond downloadAPK" href="https://apps.apk4free.net/example-notes/Example-Notes-Final.apk">Example Notes Premium APK</a>"""
        val release = listing(site(app, download)).releases.single()
        assertEquals("2.5.1", release.version)
    }

    @Test
    fun aPageWithoutDownloadsHasNoRelease() {
        val app = Fixtures.text("store/apk4free_app.html").replace("https://apk4free.net/example-notes/download/", "https://apk4free.net/example-notes/#comments")
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, app)).kind)
        val elsewhere = """<a class="buttond downloadAPK" href="https://files.example.org/Example-Notes-v2.5.1.apk">Mirror</a>"""
        assertEquals(SourceErrorKind.NO_RELEASES, failure(site(download = elsewhere)).kind)
    }

    @Test
    fun aMissingPageIsNotFoundAndAFailingOneIsANetworkProblem() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 500)).kind)
    }
}
