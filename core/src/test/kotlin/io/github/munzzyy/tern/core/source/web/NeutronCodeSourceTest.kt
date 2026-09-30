package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
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
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class NeutronCodeSourceTest {
    private val source = NeutronCodeSource()
    private val page = "https://neutroncode.com/downloads/file/3-exampleplayerarm64"
    private val spec = SourceSpec(source.type, page)
    private val fixture = Fixtures.text("web/neutroncode_file.html")

    private fun listing(html: String = fixture): SourceListing =
        (source.check(spec, CheckContext(FakeHttp().text(page, html), InMemoryValidatorStore())) as CheckResult.Listing).listing

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
    fun matchesADownloadPageInItsCanonicalForm() {
        for (url in listOf(
            page,
            "https://www.neutroncode.com/downloads/file/3-exampleplayerarm64/",
            "http://neutroncode.com/downloads/file/3-exampleplayerarm64/anything?x=1",
            "neutroncode.com/downloads/file/3-exampleplayerarm64",
        )) {
            assertEquals(url, spec, source.match(url))
        }
    }

    @Test
    fun leavesOtherPagesAndHostsAlone() {
        for (url in listOf(
            "https://neutroncode.com/",
            "https://neutroncode.com/downloads/category/1-generic-android",
            "https://neutroncode.com/downloads/file/",
            "https://forum.neutroncode.com/downloads/file/3-exampleplayerarm64",
            "https://neutroncode.com.example.org/downloads/file/3-exampleplayerarm64",
            "https://example.org/downloads/file/3-exampleplayerarm64",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun readsTheFileItsVersionDateAndNotes() {
        val listing = listing()
        val release = listing.releases.single()
        assertEquals("3.4.1", release.version)
        assertEquals("3.4.1", release.id)
        assertNull(release.versionCode)
        assertEquals(1772841600000L, release.publishedAtMs)
        assertEquals("3.4.1 (03.2026):<br />! Fixed:<br /> - Playback stopping after a call", release.notes)
        assertEquals(NotesFormat.HTML, release.notesFormat)
        assertEquals(page, release.pageUrl)
        assertEquals(listOf(Asset("ExamplePlayer_ARM64.apk", "https://neutroncode.com/download/ExamplePlayer_ARM64.apk")), release.assets)
        assertEquals("Example Player (ARM64)", listing.name)
        assertEquals("Neutron Code", listing.author)
        assertEquals("Package for 64-bit ARM processors & Android 9 or later.", listing.description)
    }

    @Test
    fun readsDatesInEitherOrder() {
        val april19 = 1776556800000L
        assertEquals(april19, listing(fixture.replace("7 March 2026", "April 19, 2026")).releases.single().publishedAtMs)
        assertEquals(april19, listing(fixture.replace("7 March 2026", "2026 april 19")).releases.single().publishedAtMs)
        assertNull(listing(fixture.replace("7 March 2026", "soon")).releases.single().publishedAtMs)
        assertNull(listing(fixture.replace("7 March 2026", "31 February 2026")).releases.single().publishedAtMs)
    }

    @Test
    fun aFileNameIsNeverTakenForAnAddress() {
        for (name in listOf("https://files.example.org/Example.apk", "../../Example.apk", "sub/Example.apk", "ExampleSetup.exe")) {
            val e = failure(FakeHttp().text(page, fixture.replace("ExamplePlayer_ARM64.apk", name)))
            assertEquals(name, SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    @Test
    fun aPageWithoutAVersionCannotBeRead() {
        val e = failure(FakeHttp().text(page, fixture.replace("<div class=\"pd-version-txt\">Version:</div><div class=\"pd-fl-m\">3.4.1</div>", "")))
        assertEquals(SourceErrorKind.PARSE, e.kind)
    }

    @Test
    fun aMissingPageIsNotFoundAndAFailingOneIsANetworkProblem() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 503)).kind)
    }
}
