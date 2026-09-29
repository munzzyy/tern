package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.Headers
import io.github.munzzyy.stamp.core.net.HttpResponse
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.testing.FakeHttp
import org.junit.Assert.assertEquals
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
}
