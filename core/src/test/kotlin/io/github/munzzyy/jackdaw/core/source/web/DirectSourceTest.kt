package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.net.InMemoryValidatorStore
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSourceTest {
    private val source = DirectSource()

    @Test
    fun matchesHttpsApkUrl() {
        val spec = source.match("https://example.com/downloads/app.apk")
        assertEquals("https://example.com/downloads/app.apk", spec?.url)
    }

    @Test
    fun doesNotMatchNonApkUrl() {
        assertNull(source.match("https://example.com/downloads/app.zip"))
    }

    @Test
    fun upgradesATypedHttpAddress() {
        assertEquals("https://example.com/app.apk", source.match("http://example.com/app.apk")?.url)
        assertNull(source.match("ftp://example.com/app.apk"))
    }

    @Test
    fun usesEtagAsIdentity() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) { HttpResponse.of(200, "", Headers.of("ETag" to "\"v1\"", "Content-Length" to "42"), url) }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("\"v1\"", listing.releases[0].id)
        assertEquals(42L, listing.releases[0].assets[0].size)
    }

    @Test
    fun retriesAsRangeGetWhenHeadIsForbidden() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) { request ->
            if (request.method == "HEAD") HttpResponse.of(403, "", Headers.EMPTY, url)
            else HttpResponse.of(206, "x", Headers.of("Content-Range" to "bytes 0-0/999"), url)
        }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals(999L, listing.releases[0].assets[0].size)
    }

    @Test
    fun notModifiedReturnsUnchanged() {
        val url = "https://example.com/app.apk"
        val store = InMemoryValidatorStore()
        val http = FakeHttp().on(url) { HttpResponse.of(304, "", Headers.EMPTY, url) }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, store))
        assertTrue(result is CheckResult.Unchanged)
    }

    @Test
    fun versionFromLastModifiedDate() {
        val url = "https://example.com/app.apk"
        val http = FakeHttp().on(url) {
            HttpResponse.of(200, "", Headers.of("Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT", "Content-Length" to "5"), url)
        }
        val result = source.check(SourceSpec(source.type, url), CheckContext(http, InMemoryValidatorStore()))
        val listing = (result as CheckResult.Listing).listing
        assertEquals("2015.10.21", listing.releases[0].version)
    }
}
