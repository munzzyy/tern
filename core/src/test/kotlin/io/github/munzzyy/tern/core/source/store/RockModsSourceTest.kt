package io.github.munzzyy.tern.core.source.store

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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RockModsSourceTest {
    private val source = RockModsSource()
    private val page = "https://www.rockmods.net/apps/example-player-mod-apk"
    private val spec = SourceSpec(source.type, page)
    private val fixture = Fixtures.text("store/rockmods_app.html")

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
    fun isFollowedWithoutFiles() {
        assertTrue(source.trackOnly)
        assertTrue(source.republishes)
    }

    @Test
    fun matchesAnAppPageInItsCanonicalForm() {
        for (url in listOf(page, "https://rockmods.net/apps/example-player-mod-apk", "http://rockmods.net/Apps/example-player-mod-apk/download?x=1")) {
            assertEquals(url, spec, source.match(url))
        }
    }

    @Test
    fun leavesOtherPagesAndHostsAlone() {
        for (url in listOf(
            "https://www.rockmods.net/",
            "https://www.rockmods.net/apps",
            "https://www.rockmods.net/categories/video-players-editors",
            "https://rockmods.net.example.org/apps/example-player-mod-apk",
            "https://example.org/apps/example-player-mod-apk",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun readsTheSoftwareApplicationOfThePage() {
        val listing = listing()
        assertEquals("Example Player (Pro)(Mod)", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("A video player with subtitles & more", listing.description)
        val release = listing.releases.single()
        assertEquals("3.2.4", release.version)
        assertEquals("3.2.4", release.id)
        assertEquals(page, release.pageUrl)
        assertEquals(emptyList<Any>(), release.assets)
        assertNull("an icon on another host is not taken", listing.iconUrl)
    }

    @Test
    fun theHeadingStandsInForAMissingName() {
        val html = fixture.replace("\"name\":\"Example Player (Pro)(Mod)\",", "")
        assertEquals("Example Player (Pro)(Mod)", listing(html).name)
    }

    @Test
    fun findsTheApplicationInsideAGraph() {
        val graph = """<script type="application/ld+json">{"@graph":[{"@type":"WebPage"},{"@type":["SoftwareApplication","MobileApplication"],"name":"Example Player","softwareVersion":3.3}]}</script>"""
        val listing = listing("<html><head>$graph</head><body></body></html>")
        assertEquals("3.3", listing.releases.single().version)
        assertEquals("Example Player", listing.name)
    }

    @Test
    fun aBrokenBlockIsPassedOver() {
        val broken = """<script type="application/ld+json">{"@type": "SoftwareApplication", oops</script>"""
        assertEquals("3.2.4", listing(fixture.replace("<head>", "<head>$broken")).releases.single().version)
    }

    @Test
    fun aPageWithoutAVersionCannotBeRead() {
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().text(page, fixture.replace("\"softwareVersion\":\"3.2.4\",", ""))).kind)
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().text(page, "<html><h1>Example Player</h1></html>")).kind)
    }

    @Test
    fun aMissingPageIsNotFoundAndAFailingOneIsANetworkProblem() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(page, "", status = 520)).kind)
    }
}
