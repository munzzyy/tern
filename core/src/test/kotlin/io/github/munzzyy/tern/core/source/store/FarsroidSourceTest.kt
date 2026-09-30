package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class FarsroidSourceTest {
    private val source = FarsroidSource()
    private val page = "https://www.farsroid.com/example-app"
    private val box = "https://www.farsroid.com/api/download-box/?post_id=4242&post_version=2.1.0"

    private fun spec() = SourceSpec(SourceTypes.FARSROID, page)

    private fun context(http: FakeHttp) = CheckContext(http, InMemoryValidatorStore())

    private fun check(http: FakeHttp): SourceListing = (source.check(spec(), context(http)) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp): SourceErrorKind = try {
        source.check(spec(), context(http))
        fail("expected SourceException")
        error("unreachable")
    } catch (e: SourceException) {
        e.kind
    }

    @Test
    fun matchesAnAppPost() {
        assertEquals(SourceSpec(SourceTypes.FARSROID, page), source.match("https://www.farsroid.com/example-app/"))
        assertEquals(page, source.match("http://farsroid.com/example-app/#download")?.url)
        assertEquals("https://www.farsroid.com/%D9%86%D9%85%D9%88%D9%86%D9%87", source.match("https://www.farsroid.com/%D9%86%D9%85%D9%88%D9%86%D9%87/")?.url)
    }

    @Test
    fun leavesOtherAddressesAlone() {
        assertNull(source.match("https://www.farsroid.com/"))
        assertNull(source.match("https://www.farsroid.com/api/download-box/?post_id=4242"))
        assertNull(source.match("https://www.farsroid.com/category/games/"))
        assertNull(source.match("https://dl2.farsroid.com/app/Example-App.apk"))
        assertNull(source.match("https://www.farsroid.com/app/Example-App.apk"))
        assertNull(source.match("https://example.org/example-app"))
        assertNull(source.match("https://www.farsroid.com.example.org/example-app"))
    }

    @Test
    fun readsTheFilesOfTheDownloadBox() {
        val http = FakeHttp().resource(page, "store/farsroid_page.html").resource(box, "store/farsroid_box.json")
        val release = check(http).releases.single()

        assertEquals("2.1.0", release.id)
        assertEquals("2.1.0", release.version)
        assertEquals(page, release.pageUrl)
        assertEquals(
            listOf(
                "https://dl2.farsroid.com/app/Example-App-2.1.0-arm64-v8a(www.farsroid.com).apk",
                "https://dl2.farsroid.com/app/Example-App-2.1.0-armeabi-v7a(www.farsroid.com).apk",
                "https://www.farsroid.com/app/Example%20App%202.1.0%20(Mod).apk",
            ),
            release.assets.map { it.url },
        )
        assertEquals(
            listOf("Example-App-2.1.0-arm64-v8a(www.farsroid.com).apk", "Example-App-2.1.0-armeabi-v7a(www.farsroid.com).apk", "Example App 2.1.0 (Mod).apk"),
            release.assets.map { it.name },
        )
        assertEquals(listOf(page, box), http.requests.map { it.url })
    }

    @Test
    fun aFileOnAnotherHostIsLeftOut() {
        val http = FakeHttp().resource(page, "store/farsroid_page.html").resource(box, "store/farsroid_box.json")
        val addresses = check(http).releases.single().assets.map { it.url }
        assertEquals(emptyList<String>(), addresses.filter { "example.org" in it || it.startsWith("http:") || it.endsWith(".zip") })
    }

    @Test
    fun theVersionIsSentAsPartOfTheAddress() {
        val html = Fixtures.text("store/farsroid_page.html").replace("data-post-version=\"2.1.0\"", "data-post-version=\"2.1.0 beta&amp;1\"")
        val encoded = "https://www.farsroid.com/api/download-box/?post_id=4242&post_version=2.1.0%20beta%261"
        val http = FakeHttp().text(page, html).resource(encoded, "store/farsroid_box.json")
        assertEquals("2.1.0 beta&1", check(http).releases.single().version)
    }

    @Test
    fun aPageWithoutADownloadBoxHasNoRelease() {
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, "<html><body>Example App</body></html>")))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, """<ul class="download-links" data-post-id="4242"></ul>""")))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(page, """<ul class="download-links" data-post-id="../1" data-post-version="2.1.0"></ul>""")))
        val empty = FakeHttp().resource(page, "store/farsroid_page.html").text(box, """{"data":{"content":""}}""")
        assertEquals(SourceErrorKind.NO_RELEASES, failure(empty))
    }

    @Test
    fun failuresAreReported() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(page, "", status = 404)))
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().resource(page, "store/farsroid_page.html").text(box, "", status = 500)))
        assertEquals(SourceErrorKind.PARSE, failure(FakeHttp().resource(page, "store/farsroid_page.html").text(box, "<html>")))
    }
}
