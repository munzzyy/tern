package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HtmlSourceTest {
    private val source = HtmlSource()

    @Test
    fun matchAlwaysReturnsNull() {
        assertNull(source.match("https://example.com/download"))
    }

    @Test
    fun picksApkLinksAndGroupsByVersion() {
        val pageUrl = "https://example.com/download"
        val html = """
            <html><body>
              <a href="/dl/app-2.0.0.apk">Download 2.0.0</a>
              <a href="/dl/app-1.0.0.apk">Download 1.0.0</a>
              <a href="/dl/readme.txt">Read me</a>
            </body></html>
        """.trimIndent()
        val http = FakeHttp().text(pageUrl, html)
        val result = source.check(SourceSpec(source.type, pageUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(2, listing.releases.size)
        assertEquals("2.0.0", listing.releases[0].version)
        assertTrue(listing.releases[0].assets[0].url.endsWith("app-2.0.0.apk"))
    }

    @Test
    fun honoursBaseHref() {
        val pageUrl = "https://example.com/download"
        val html = """<html><head><base href="https://cdn.example.com/files/"></head>
            <body><a href="app-1.0.0.apk">get</a></body></html>"""
        val http = FakeHttp().text(pageUrl, html)
        val result = source.check(SourceSpec(source.type, pageUrl), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("https://cdn.example.com/files/app-1.0.0.apk", listing.releases[0].assets[0].url)
    }

    @Test
    fun customLinkFilterOption() {
        val pageUrl = "https://example.com/download"
        val html = """<html><body><a href="/dl/app-1.0.0.apk">apk</a><a href="/dl/app-1.0.0.apkm">bundle</a></body></html>"""
        val http = FakeHttp().text(pageUrl, html)
        val spec = SourceSpec(source.type, pageUrl, mapOf(SourceOptions.LINK_FILTER to "\\.apkm$"))
        val result = source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(1, listing.releases.size)
        assertTrue(listing.releases[0].assets[0].url.endsWith(".apkm"))
    }

    @Test
    fun versionFromTextInsteadOfLink() {
        val pageUrl = "https://example.com/download"
        val html = """<html><body><a href="/dl/download.apk">Version 3.4.5</a></body></html>"""
        val http = FakeHttp().text(pageUrl, html)
        val spec = SourceSpec(source.type, pageUrl, mapOf(SourceOptions.VERSION_FROM to "text"))
        val result = source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("3.4.5", listing.releases[0].version)
    }

    @Test
    fun followsIntermediateStepsBeforeFinalPage() {
        val startUrl = "https://example.com/project"
        val middleUrl = "https://example.com/project/latest"
        val finalUrl = "https://example.com/dl/app-1.0.0.apk"
        val http = FakeHttp()
        http.text(startUrl, """<html><body><a href="/project/latest">latest release</a></body></html>""")
        http.text(middleUrl, """<html><body><a href="/dl/app-1.0.0.apk">download</a></body></html>""")
        http.bytes(finalUrl, "apk-bytes".toByteArray())

        val steps = "[\"latest\"]"
        val spec = SourceSpec(source.type, startUrl, mapOf(SourceOptions.STEPS to steps))
        val result = source.check(spec, CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(finalUrl, listing.releases[0].assets[0].url)
    }

    @Test
    fun tooManyStepsIsUnsupported() {
        val startUrl = "https://example.com/project"
        val steps = (1..6).joinToString(",", "[", "]") { "\"step$it\"" }
        val spec = SourceSpec(source.type, startUrl, mapOf(SourceOptions.STEPS to steps))
        try {
            source.check(spec, CheckContext(FakeHttp(), InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun invalidLinkFilterPatternIsUnsupported() {
        val pageUrl = "https://example.com/download"
        val spec = SourceSpec(source.type, pageUrl, mapOf(SourceOptions.LINK_FILTER to "("))
        try {
            source.check(spec, CheckContext(FakeHttp().text(pageUrl, "<html></html>"), InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun notModifiedReturnsUnchanged() {
        val pageUrl = "https://example.com/download"
        val http = FakeHttp().on(pageUrl) { HttpResponse.of(304, "", Headers.EMPTY, pageUrl) }
        val result = source.check(SourceSpec(source.type, pageUrl), CheckContext(http, InMemoryValidatorStore()))
        assertTrue(result is CheckResult.Unchanged)
    }

    @Test
    fun noInstallableLinkThrowsNoReleases() {
        val pageUrl = "https://example.com/download"
        val http = FakeHttp().text(pageUrl, "<html><body>no downloads here</body></html>")
        try {
            source.check(SourceSpec(source.type, pageUrl), CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    private val page = "https://example.com/download"

    private fun spec(vararg options: Pair<String, String>) = SourceSpec(source.type, page, options.toMap())

    private fun listing(http: FakeHttp, spec: SourceSpec, device: DeviceProfile? = null) =
        (source.check(spec, CheckContext(http, InMemoryValidatorStore(), device = device)) as CheckResult.Listing).listing

    private fun unsupported(spec: SourceSpec, http: FakeHttp = FakeHttp()) {
        try {
            source.check(spec, CheckContext(http, InMemoryValidatorStore()))
            fail("expected SourceException for ${spec.options}")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.UNSUPPORTED, e.kind)
        }
    }

    @Test
    fun theFirstLinkIsTheFirstOnThePageOrTheLowestInNaturalOrder() {
        val http = FakeHttp().text(page, """<a href="/dl/app-1.10.apk">new</a><a href="/dl/app-1.9.apk">old</a><a href="/dl/app-1.2.apk">older</a>""")
        assertEquals(listOf("1.10", "1.9", "1.2"), listing(http, spec()).releases.map { it.version })
        assertEquals(listOf("1.2"), listing(http, spec(SourceOptions.FIRST_LINK to "true")).releases.map { it.version })
        assertEquals(listOf("1.10"), listing(http, spec(SourceOptions.FIRST_LINK to "true", SourceOptions.SORT to "page")).releases.map { it.version })
    }

    @Test
    fun theLastSegmentAloneCanGiveTheVersionAndTheOrder() {
        val http = FakeHttp().text(page, """<a href="/v2.0/app-1.5.apk">a</a><a href="/v1.0/app-1.6.apk">b</a><a href="/b/app-2.apk">c</a><a href="/a/app-3.apk">d</a>""")
        assertEquals(listOf("2.0", "1.0"), listing(http, spec(SourceOptions.PSEUDO to "link")).releases.mapNotNull { it.version.ifEmpty { null } })
        assertEquals(listOf("1.6", "1.5"), listing(http, spec(SourceOptions.LAST_SEGMENT to "true", SourceOptions.PSEUDO to "link")).releases.mapNotNull { it.version.ifEmpty { null } })

        val names = FakeHttp().text(page, """<a href="/b/app-2.apk">c</a><a href="/a/app-3.apk">d</a>""")
        fun first(vararg options: Pair<String, String>) =
            listing(names, spec(SourceOptions.FIRST_LINK to "true", SourceOptions.PSEUDO to "link", *options)).releases.single().assets.single().url
        assertEquals("https://example.com/a/app-3.apk", first())
        assertEquals("https://example.com/b/app-2.apk", first(SourceOptions.LAST_SEGMENT to "true"))
    }

    @Test
    fun addressesOutsideLinksAreFoundOnlyWhenAskedFor() {
        val html = """<html><body>
            <button data-href="/dl/app-3.0.0.apk">Get it</button>
            <p>Mirror: https://cdn.example.com/files/app-2.9.0.apk.</p>
            <a href="/dl/app-1.0.0.apk">old</a>
        </body></html>"""
        val http = FakeHttp().text(page, html)
        assertEquals(listOf("1.0.0"), listing(http, spec()).releases.map { it.version })
        val any = listing(http, spec(SourceOptions.ANY_TEXT to "true")).releases
        assertEquals(listOf("3.0.0", "2.9.0", "1.0.0"), any.map { it.version })
        assertEquals("https://cdn.example.com/files/app-2.9.0.apk", any[1].assets.single().url)
        assertEquals("https://example.com/dl/app-3.0.0.apk", any[0].assets.single().url)
    }

    @Test
    fun aPageOfTagsThatNeverCloseIsReadInOnePass() {
        val http = FakeHttp().text(page, "<x ".repeat(300_000) + """<a href="/dl/app-1.0.0.apk">get</a>""")
        assertEquals(listOf("1.0.0"), listing(http, spec(SourceOptions.ANY_TEXT to "true")).releases.map { it.version })
    }

    @Test
    fun addressesInAJsonAnswerAreFoundWhenAskedFor() {
        val json = """{"name": "Example", "latest": {"url": "https://cdn.example.com/app-4.1.0.apk", "mirror": "/files/app-4.1.0-mirror.apk"}, "notes": "see https://example.com/notes"}"""
        val http = FakeHttp().text(page, json)
        val release = listing(http, spec(SourceOptions.ANY_TEXT to "true")).releases.single()
        assertEquals("4.1.0", release.version)
        assertEquals(setOf("https://cdn.example.com/app-4.1.0.apk", "https://example.com/files/app-4.1.0-mirror.apk"), release.assets.map { it.url }.toSet())
        try {
            listing(http, spec())
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.NO_RELEASES, e.kind)
        }
    }

    @Test
    fun theHeadersGoWithThePageTheStepsTheFilesAndTheDownload() {
        val start = "https://example.com/project"
        val file = "https://example.com/dl/latest.apk"
        val http = FakeHttp()
            .text(start, """<a href="/download">downloads</a>""")
            .text(page, """<a href="/dl/latest.apk">get</a>""")
            .on(file) { HttpResponse.of(200, "", Headers.of("ETag" to "\"e1\""), file) }
        val headers = mapOf("User-Agent" to "Mozilla/5.0 Example", "X-Mirror" to "eu")
        val spec = SourceSpec(source.type, start, mapOf(SourceOptions.HEADERS to RequestHeaders.write(headers), SourceOptions.STEPS to "[\"download\"]"))
        listing(http, spec)
        assertEquals(listOf(start, page, file), http.requests.map { it.url })
        assertTrue(http.requests.all { request -> headers.all { (name, value) -> request.headers[name] == value } })
        assertEquals(Download(file, headers), source.resolve(spec, Asset("latest.apk", file), CheckContext(http, InMemoryValidatorStore())))
        assertEquals(Download(file), source.resolve(SourceSpec(source.type, start), Asset("latest.apk", file), CheckContext(http, InMemoryValidatorStore())))
    }

    @Test
    fun aRefusedHeaderFailsTheCheckAsAnUnsupportedOption() {
        for (headers in listOf("""{"Authorization": "Bearer x"}""", """{"Cookie": "a=b"}""", """{"If-None-Match": "x"}""", """{"X-Bad": "a\nb"}""", "not json", """{"X-A": 1}""")) {
            unsupported(spec(SourceOptions.HEADERS to headers))
        }
    }

    @Test
    fun aFileWithoutAVersionCanBeToldApartByItsFirstBytes() {
        val file = "https://example.com/dl/latest.apk"
        val first = ByteArray(4096) { 1 }
        fun id(http: FakeHttp): String {
            val release = listing(http.text(page, """<a href="/dl/latest.apk">get</a>"""), spec(SourceOptions.PSEUDO to "hash")).releases.single()
            assertEquals("bytes=0-1023", http.requestsTo(file).single().headers["Range"])
            return release.id
        }
        fun serving(bytes: ByteArray) = FakeHttp().bytes(file, bytes)
        assertEquals(id(serving(first)), id(serving(first.copyOf())))
        assertNotEquals(id(serving(first)), id(serving(first.copyOf().also { it[10] = 2 })))
        assertEquals(id(serving(first)), id(serving(first.copyOf().also { it[2000] = 2 })))
        assertEquals(id(serving(first)), id(FakeHttp().on(file) { HttpResponse.of(200, first, url = file) }))
        assertEquals(4096L, listing(serving(first).text(page, """<a href="/dl/latest.apk">get</a>"""), spec(SourceOptions.PSEUDO to "hash")).releases.single().assets.single().size)
    }

    @Test
    fun aLinkHashAsksNothingOfTheFileAndLetsAnUnchangedPageRest() {
        val http = FakeHttp().on(page) { request ->
            if (request.headers["If-None-Match"] == "\"p1\"") {
                HttpResponse.of(304, "", url = page)
            } else {
                HttpResponse.of(200, """<a href="/dl/latest.apk">get</a>""", Headers.of("ETag" to "\"p1\""), page)
            }
        }
        val context = CheckContext(http, InMemoryValidatorStore())
        val spec = spec(SourceOptions.PSEUDO to "link")
        val release = (source.check(spec, context) as CheckResult.Listing).listing.releases.single()
        assertTrue(release.id.startsWith("file:link:"))
        assertEquals(CheckResult.Unchanged, source.check(spec, context))
        assertTrue(http.requests.none { it.url.endsWith(".apk") })
    }

    @Test
    fun theEtagAloneCanBeWhatTellsFilesApart() {
        val file = "https://example.com/dl/latest.apk"
        val http = FakeHttp().text(page, """<a href="/dl/latest.apk">get</a>""")
            .on(file) { HttpResponse.of(200, "", Headers.of("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT"), file) }
        assertEquals("file:Wed, 21 Oct 2015 07:28:00 GMT", listing(http, spec()).releases.single().id)
        assertEquals("file:$file", listing(http, spec(SourceOptions.PSEUDO to "etag")).releases.single().id)
        unsupported(spec(SourceOptions.PSEUDO to "partialAPKHash"))
    }

    @Test
    fun aStepCanMatchTheLinkTextInsteadOfTheAddress() {
        val start = "https://example.com/project"
        val http = FakeHttp()
            .text(start, """<a href="/r/123">Nightly builds</a><a href="/r/456">Stable releases</a>""")
            .text("https://example.com/r/456", """<a href="/dl/app-2.0.0.apk">get</a>""")
        val byText = SourceSpec(source.type, start, mapOf(SourceOptions.STEPS to """[{"filter": "^Stable", "text": true}]"""))
        assertEquals("2.0.0", listing(http, byText).releases.single().version)
        unsupported(SourceSpec(source.type, start, mapOf(SourceOptions.STEPS to """[{"filter": "^Stable"}]""")), http)
        unsupported(SourceSpec(source.type, start, mapOf(SourceOptions.STEPS to """[{"text": true}]""")), http)
    }

    @Test
    fun aStepCanPreferTheLinkForThisDevicesProcessor() {
        val start = "https://example.com/project"
        val http = FakeHttp()
            .text(start, """<a href="/b/x86">x86 build</a><a href="/b/universal">universal</a><a href="/b/arm64">arm64 build</a><a href="/b/armv7">arm build</a>""")
            .text("https://example.com/b/arm64", """<a href="/dl/app-arm64-2.0.0.apk">get</a>""")
            .text("https://example.com/b/universal", """<a href="/dl/app-2.0.0.apk">get</a>""")
            .text("https://example.com/b/x86", """<a href="/dl/app-x86-2.0.0.apk">get</a>""")
        val arch = SourceSpec(source.type, start, mapOf(SourceOptions.STEPS to """[{"filter": "/b/", "arch": true}]"""))
        fun chosen(device: DeviceProfile?, spec: SourceSpec = arch) = listing(http, spec, device).releases.single().assets.single().url
        assertEquals("https://example.com/dl/app-arm64-2.0.0.apk", chosen(DeviceProfile.ARM64_PHONE))
        assertEquals("https://example.com/dl/app-x86-2.0.0.apk", chosen(DeviceProfile(listOf("x86"), sdk = 34, densityDpi = 320)))
        assertEquals("https://example.com/dl/app-2.0.0.apk", chosen(DeviceProfile(listOf("riscv64"), sdk = 34, densityDpi = 320)))
        assertEquals("https://example.com/dl/app-x86-2.0.0.apk", chosen(null))
        assertEquals("https://example.com/dl/app-x86-2.0.0.apk", chosen(DeviceProfile.ARM64_PHONE, SourceSpec(source.type, start, mapOf(SourceOptions.STEPS to "[\"/b/\"]"))))
    }
}
