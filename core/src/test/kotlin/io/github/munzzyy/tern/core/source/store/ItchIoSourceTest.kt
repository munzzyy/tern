package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
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

class ItchIoSourceTest {
    private val source = ItchIoSource()
    private val base = "https://examplelabs.itch.io/example-quest"
    private val spec = SourceSpec(source.type, base)
    private val token = "WyJhYmMiLDE3OTA3MzAyNjEsIkV4YW1wbGUiXQ==.c2lnbmF0dXJl"
    private val downloadPage = "$base/download/eyJpZCI6MX0%3d"
    private val fileStore = "https://itchio-mirror.cb031a832f44726753d6267436f3b414.r2.cloudflarestorage.com"

    private fun fileEndpoint(id: Int) = "$base/file/$id?as_props=1&source=game_download"

    private fun json(url: String, text: String, status: Int = 200) =
        { _: HttpRequest -> HttpResponse.of(status, text, Headers.of("Content-Type" to "application/json"), url) }

    /** The game page of a "name your own price" game, and everything behind it. */
    private fun game(): FakeHttp = FakeHttp()
        .resource(base, "store/itchio_game.html")
        .on("$base/download_url", json("$base/download_url", """{"url":"https:\/\/examplelabs.itch.io\/example-quest\/download\/eyJpZCI6MX0%3d"}"""))
        .resource(downloadPage, "store/itchio_download.html")
        .on(fileEndpoint(1002), json(fileEndpoint(1002), """{"external":false,"url":"$fileStore/upload2/game/1/1002?X-Amz-Expires=60&X-Amz-Signature=abc"}"""))
        .on("$fileStore/upload2/game/1/1002?X-Amz-Expires=60&X-Amz-Signature=abc") { request ->
            HttpResponse.of(
                206,
                "P",
                Headers.of(
                    "Content-Disposition" to "attachment; filename=\"ExampleQuest-2.3.0.apk\"",
                    "Content-Range" to "bytes 0-0/12345678",
                    "Content-Length" to "1",
                ),
                request.url,
            )
        }
        .on(fileEndpoint(1003), json(fileEndpoint(1003), """{"external":true,"url":"https://files.example.org/ExampleQuest.apk"}"""))
        .on(fileEndpoint(1004), json(fileEndpoint(1004), "", status = 500))

    private fun listing(http: FakeHttp): SourceListing =
        (source.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing

    private fun failure(http: FakeHttp, block: (CheckContext) -> Unit = { source.check(spec, it) }): SourceException {
        try {
            block(CheckContext(http, InMemoryValidatorStore()))
        } catch (e: SourceException) {
            return e
        }
        fail("expected a SourceException")
        throw AssertionError()
    }

    @Test
    fun matchesAGameInItsCanonicalForm() {
        for (url in listOf(
            base,
            "http://ExampleLabs.itch.io/example-quest/devlog/123/a-post",
            "examplelabs.itch.io/example-quest?secret=1",
            "https://examplelabs.itch.io/example-quest/",
        )) {
            assertEquals(url, spec, source.match(url))
        }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        for (url in listOf(
            "https://itch.io/games/free/platform-android",
            "https://www.itch.io/example-quest",
            "https://examplelabs.itch.io/",
            "https://a.b.itch.io/example-quest",
            "https://examplelabs.itch.io.example.org/example-quest",
            "https://example.org/example-quest",
        )) {
            assertNull(url, source.match(url))
        }
    }

    @Test
    fun readsTheAndroidFilesBehindThePrice() {
        val http = game()
        val listing = listing(http)
        assertEquals("Example Quest", listing.name)
        assertEquals("Example Labs", listing.author)
        assertEquals("A small puzzle adventure & more", listing.description)
        assertNull(listing.packageName)

        val release = listing.releases.single()
        assertEquals("2.3.0", release.version)
        assertEquals("2.3.0", release.id)
        assertEquals(1789912080000L, release.publishedAtMs)
        assertEquals(base, release.pageUrl)
        assertEquals(
            listOf(
                Asset("ExampleQuest-2.3.0.apk", "$base/download/1002", size = 12345678L),
                Asset("example-quest-arm64.apk", "$base/download/1004"),
            ),
            release.assets,
        )

        val posts = http.requests.filter { it.method == "POST" }
        assertEquals(listOf("$base/download_url", fileEndpoint(1002), fileEndpoint(1003), fileEndpoint(1004)), posts.map { it.url })
        for (post in posts) {
            assertEquals("""{"csrf_token":"$token"}""", String(post.body!!, Charsets.UTF_8))
            assertEquals("application/json", post.headers["Content-Type"])
            assertEquals("XMLHttpRequest", post.headers["X-Requested-With"])
        }
        assertEquals("$base/download/1002", posts[1].headers["Referer"])
        assertTrue("no cookie is ever sent", http.requests.none { request -> request.headers.keys.any { it.equals("Cookie", ignoreCase = true) } })
    }

    @Test
    fun aFreeGameIsReadFromItsOwnPage() {
        val page = """
            <html><head><title>Example Quest by Example Labs</title></head><body>
            <div class="view_game_page page_widget base_widget">
            <table><tr><td>Updated</td><td><abbr title="20 September 2026 @ 13:48 UTC">9 days ago</abbr></td></tr></table>
            <div class="upload"><a class="button download_btn" data-upload_id="2001" href="javascript:void(0);">Download</a>
            <div class="upload_name"><strong class="name" title="example-quest.apk">example-quest.apk</strong>
            <span class="download_platforms"><span class="icon icon-android"></span></span></div></div>
            </div></body></html>
        """.trimIndent()
        val http = FakeHttp().text(base, page)
        val release = listing(http).releases.single()
        assertEquals("20260920", release.version)
        assertEquals(listOf(Asset("example-quest.apk", "$base/download/2001")), release.assets)
        assertEquals(listOf(base), http.requests.map { it.url })
    }

    @Test
    fun aPageWithoutVersionOrDateGivesLatest() {
        val page = """
            <html><body><div class="page_widget">
            <div class="upload"><a class="download_btn" data-upload_id="2001">Download</a>
            <strong class="name" title="example-quest.apk">example-quest.apk</strong>
            <span class="download_platforms"><span class="icon icon-android"></span></span></div>
            </div></body></html>
        """.trimIndent()
        assertEquals("latest", listing(FakeHttp().text(base, page)).releases.single().version)
    }

    @Test
    fun aGameWithoutAnAndroidFileHasNoRelease() {
        val page = """
            <html><body><div class="page_widget"><div class="upload"><a class="download_btn" data-upload_id="2001">Download</a>
            <strong class="name" title="example-quest.zip">example-quest.zip</strong>
            <span class="download_platforms"><span class="icon icon-windows8"></span></span></div></div>
            <span class="icon icon-android"></span></body></html>
        """.trimIndent()
        assertEquals(SourceErrorKind.NO_RELEASES, failure(FakeHttp().text(base, page)).kind)
    }

    @Test
    fun anAndroidFileKeptOnAnotherSiteIsLeftOut() {
        val page = """
            <html><head><meta name="csrf_token" value="$token"/></head><body><div class="page_widget">
            <div class="upload"><a class="download_btn" data-upload_id="1003">Download</a>
            <strong class="name" title="Example Quest (mirror)">Example Quest (mirror)</strong>
            <span class="download_platforms"><span class="icon icon-android"></span></span></div></div></body></html>
        """.trimIndent()
        val http = FakeHttp().text(base, page)
            .on(fileEndpoint(1003), json(fileEndpoint(1003), """{"external":true,"url":"https://files.example.org/ExampleQuest.apk"}"""))
        assertEquals(SourceErrorKind.NO_RELEASES, failure(http).kind)
        assertTrue(http.requests.none { it.url.startsWith("https://files.example.org") })
    }

    @Test
    fun aMissingGameIsNotFoundAndAFailingPageIsANetworkProblem() {
        assertEquals(SourceErrorKind.NOT_FOUND, failure(FakeHttp().text(base, "", status = 404)).kind)
        assertEquals(SourceErrorKind.NETWORK, failure(FakeHttp().text(base, "", status = 503)).kind)
    }

    @Test
    fun resolveAsksForAFreshAddressOfTheUpload() {
        val http = game()
        val download = source.resolve(spec, Asset("ExampleQuest-2.3.0.apk", "$base/download/1002"), CheckContext(http, InMemoryValidatorStore()))
        assertEquals("$fileStore/upload2/game/1/1002?X-Amz-Expires=60&X-Amz-Signature=abc", download.url)
        assertEquals(emptyMap<String, String>(), download.headers)
        assertEquals(listOf("GET $base", "POST ${fileEndpoint(1002)}"), http.requests.map { "${it.method} ${it.url}" })
    }

    @Test
    fun resolveRefusesAnAddressOutsideTheFileStore() {
        val e = failure(game()) { source.resolve(spec, Asset("Example Quest (mirror)", "$base/download/1003"), it) }
        assertEquals(SourceErrorKind.PARSE, e.kind)
    }

    @Test
    fun resolveSaysWhenTheUploadIsGone() {
        val http = game().on(fileEndpoint(1009), json(fileEndpoint(1009), "", status = 404))
        val e = failure(http) { source.resolve(spec, Asset("old.apk", "$base/download/1009"), it) }
        assertEquals(SourceErrorKind.NOT_FOUND, e.kind)
    }

    @Test
    fun resolveTakesOnlyTheFilesItListed() {
        val http = FakeHttp()
        for (url in listOf("https://examplelabs.itch.io/other-game/download/1002", "$base/download/../../x", "https://files.example.org/x.apk")) {
            val e = failure(http) { source.resolve(spec, Asset("x.apk", url), it) }
            assertEquals(url, SourceErrorKind.PARSE, e.kind)
        }
        assertEquals(emptyList<HttpRequest>(), http.requests)
    }
}
