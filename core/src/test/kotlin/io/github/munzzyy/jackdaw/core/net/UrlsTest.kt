package io.github.munzzyy.jackdaw.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {
    private val normalizeCases = listOf(
        "example.com" to "https://example.com",
        "example.com/path" to "https://example.com/path",
        "http://example.com" to "https://example.com",
        "https://example.com" to "https://example.com",
        "https://EXAMPLE.com/Path" to "https://example.com/Path",
        "https://example.com:443" to "https://example.com",
        "http://example.com:80" to "https://example.com",
        "https://example.com:8443/x" to "https://example.com:8443/x",
        "https://example.com/a//b" to "https://example.com/a//b",
        "https://example.com/a?x=1#frag" to "https://example.com/a?x=1",
        "https://example.com/a#frag" to "https://example.com/a",
        "  https://example.com  " to "https://example.com",
        "//example.com" to null,
        "" to null,
        "   " to null,
        "ftp://example.com" to null,
        "javascript:alert(1)" to null,
        "data:text/plain;base64,AA==" to null,
        "mailto:a@example.com" to null,
        "https://user:pass@example.com" to null,
        "https://user@example.com" to null,
        "https://" to null,
        "https:///path" to null,
        "not a url at all" to null,
        "example.com/../etc" to "https://example.com/../etc",
        "https://xn--e1aybc.xn--p1ai" to "https://xn--e1aybc.xn--p1ai",
        "https://example.com/PATH" to "https://example.com/PATH",
        "https://example.com./" to "https://example.com./",
        "HTTPS://example.com" to "https://example.com",
        "HtTp://example.com" to "https://example.com",
        "https://example.com/a b" to null,
        "https://example.com/a%20b" to "https://example.com/a%20b",
        "https://[::1]" to "https://[::1]",
        "https://[::1]:443" to "https://[::1]",
        "https://example.com:0" to "https://example.com:0",
        "https://example.com:-1" to null,
        "https://example.com:99999" to null,
        "https://.com" to null,
        "vnd.example://thing" to null,
        "https://example.com?x=1" to "https://example.com?x=1",
        "https://example.com/%2e%2e" to "https://example.com/%2e%2e",
        "https://example.com/a/./b" to "https://example.com/a/./b",
        "https://EXAMPLE.COM:443/Foo?Bar=1" to "https://example.com/Foo?Bar=1",
        "github.com/example/app" to "https://github.com/example/app",
        "www.github.com/example/app" to "https://www.github.com/example/app",
        "https://example.com/a\tb" to null,
        "https://example.com\n" to "https://example.com",
        "https://exa mple.com" to null,
        "https://example.com:abc" to null,
        "https://gitlab.com/group/sub/project" to "https://gitlab.com/group/sub/project",
        "https://example.com/a;b" to "https://example.com/a;b",
        "https://example.com/a+b" to "https://example.com/a+b",
        "https://example.com/a%2Fb" to "https://example.com/a%2Fb",
        "https://example.com/a?b=c&d=e" to "https://example.com/a?b=c&d=e",
    )

    @Test
    fun normalizeTable() {
        for ((input, expected) in normalizeCases) {
            assertEquals("input: $input", expected, Urls.normalize(input))
        }
    }

    @Test
    fun hostReadsBackNormalizedHost() {
        assertEquals("github.com", Urls.host("https://github.com/example/app"))
        assertEquals("", Urls.host("not a url"))
    }

    @Test
    fun segmentsSplitsDecodedPath() {
        assertEquals(listOf("example", "app releases"), Urls.segments("https://github.com/example/app%20releases"))
        assertEquals(emptyList<String>(), Urls.segments("https://github.com"))
    }

    @Test
    fun encodeSegmentEscapesSlashAndSpace() {
        assertEquals("group%2Fproject", Urls.encodeSegment("group/project"))
        assertEquals("a%20b", Urls.encodeSegment("a b"))
        assertEquals("a%2Bb", Urls.encodeSegment("a+b"))
    }

    @Test
    fun queryParamFindsDecodedValue() {
        assertEquals("1", Urls.queryParam("https://example.com/a?x=1&y=2", "x"))
        assertEquals("a b", Urls.queryParam("https://example.com/a?q=a%20b", "q"))
        assertNull(Urls.queryParam("https://example.com/a?x=1", "missing"))
    }

    @Test
    fun resolveJoinsRelativeHref() {
        assertEquals("https://example.com/a/b", Urls.resolve("https://example.com/a/", "b"))
        assertEquals("https://example.com/b", Urls.resolve("https://example.com/a/", "/b"))
    }

    @Test
    fun resolveRejectsHostileSchemes() {
        assertNull(Urls.resolve("https://example.com/a/", "javascript:alert(1)"))
        assertNull(Urls.resolve("https://example.com/a/", "data:text/plain,hi"))
        assertNull(Urls.resolve("https://example.com/a/", "mailto:a@example.com"))
        assertNull(Urls.resolve("https://example.com/a/", "http://insecure.example.com/x"))
    }

    @Test
    fun negativeControlDetectsBrokenNormalize() {
        fun brokenNormalize(input: String): String? = input.trim()
        assertEquals("https://example.com", Urls.normalize("example.com"))
        assert(brokenNormalize("example.com") != Urls.normalize("example.com"))
    }
}
