package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiteApksSourceTest {
    private val source = LiteApksSource()
    private val page = "https://liteapks.com/example-app.html"
    private val spec = SourceSpec(source.type, page)
    private val search = "https://liteapks.com/wp-json/wp/v2/posts?slug=example-app"
    private val record = "https://liteapks.com/wp-json/v2/posts/4321"

    private fun site(post: String = Fixtures.text("store/liteapks_post.json")): FakeHttp =
        FakeHttp().resource(search, "store/liteapks_posts.json").text(record, post)

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
        for (url in listOf(page, "https://www.liteapks.com/example-app.html", "liteapks.com/example-app", "http://liteapks.com/example-app.html/amp?x=1")) {
            assertEquals(url, spec, source.match(url))
        }
    }

    @Test
    fun leavesOtherPagesAndHostsAlone() {
        for (url in listOf(
            "https://liteapks.com/",
            "https://liteapks.com/category/apps",
            "https://liteapks.com/download/example-app-4321",
            "https://liteapks.com/example%20app.html",
            "https://liteapks.com.example.org/example-app.html",
            "https://example.org/example-app.html",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun readsTheNewestVersionAndItsDownloadsThroughTheApi() {
        val http = site()
        val listing = listing(http)
        assertEquals("Example App & Friends", listing.name)
        assertEquals("Example Labs", listing.author)
        val release = listing.releases.single()
        assertEquals("5.6.1", release.version)
        assertEquals("5.6.1", release.id)
        assertEquals(page, release.pageUrl)
        assertEquals(listOf(Asset("Example App-5.6.1-mod.apk", "https://dl.liteapks.com/files/Example%20App-5.6.1-mod.apk")), release.assets)
        assertEquals(listOf(search, record), http.requests.map { it.url })
        assertTrue("each request names itself as where it comes from", http.requests.all { it.headers["Referer"] == it.url })
    }

    @Test
    fun aSlugTheSiteDoesNotKnowIsNotFound() {
        val http = FakeHttp().text(search, "[]")
        assertEquals(SourceErrorKind.NOT_FOUND, failure(http).kind)
    }

    @Test
    fun answersThatCannotBeReadAreSaidSo() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(search, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(search, "<html>Just a moment...</html>", status = 403)).kind)
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().text(search, "<html>not json</html>")).kind)
        assertEquals(SourceErrorKind.PARSE, failure(site("""{"data":{"title":"Example App","versions":[]}}""")).kind)
    }

    @Test
    fun aVersionWithoutAFileOnTheSiteHasNoRelease() {
        val post = """{"data":{"title":"Example App","versions":[{"version":"5.6.1","version_downloads":[{"version_download_link":"https://files.example.org/Example.apk"}]}]}}"""
        assertEquals(SourceErrorKind.NO_RELEASES, failure(site(post)).kind)
    }

    @Test
    fun resolveAddsTheTokenAndThePage() {
        val context = CheckContext(FakeHttp(), InMemoryValidatorStore(), nowMs = { 1_700_000_000_000L })
        val asset = Asset("Example App-5.6.1-mod.apk", "https://dl.liteapks.com/files/Example%20App-5.6.1-mod.apk")
        val download = SourceRegistry(listOf(source)).resolve(spec, asset, context)
        assertEquals("https://dl.liteapks.com/files/Example%20App-5.6.1-mod.apk?token=TVRjd01EQXhNRGd3TUE9PQ%3D%3D", download.url)
        assertEquals(mapOf("Referer" to page), download.headers)

        val queried = source.resolve(spec, Asset("x.apk", "https://dl.liteapks.com/get?id=7"), context)
        assertEquals("https://dl.liteapks.com/get?id=7&token=TVRjd01EQXhNRGd3TUE9PQ%3D%3D", queried.url)
    }

    @Test
    fun resolveRefusesAFileElsewhere() {
        try {
            source.resolve(spec, Asset("x.apk", "https://files.example.org/x.apk"), CheckContext(FakeHttp(), InMemoryValidatorStore()))
            fail("resolved a file on another host")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }
}
