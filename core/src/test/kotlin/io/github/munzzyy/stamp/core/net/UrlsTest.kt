package io.github.munzzyy.stamp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun theAuthorityKeepsAPortThatIsNotTheUsualOne() {
        assertEquals("git.example.org", Urls.authority("https://git.example.org/group/app"))
        assertEquals("git.example.org", Urls.authority("https://git.example.org:443/group/app"))
        assertEquals("git.example.org:8443", Urls.authority("https://GIT.example.org:8443/group/app"))
        assertEquals("", Urls.authority("not an address"))
    }

    @Test
    fun anAddressOnTheDeviceOrItsNetworkIsLocalHoweverItIsSpelled() {
        val local = listOf(
            "127.0.0.1", "127.1", "0x7f.0.0.1", "0x7f.1", "017700000001", "2130706433", "0.0.0.0", "0",
            "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.169.254",
            "100.64.0.1", "100.127.255.255", "224.0.0.251", "255.255.255.255",
            "[::1]", "::1", "[::]", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1%wlan0]", "[ff02::1]",
            "[64:ff9b::a00:1]", "[64:ff9b::10.0.0.1]",
            "localhost", "LOCALHOST", "localhost.", "printer.local", "nas.lan", "git.internal", "forge.home.arpa", "router", "",
            "1.2.3.4.5", "999.1.1.1", "12.0x", "0x",
        )
        for (host in local) assertTrue("$host should count as local", Urls.isLocal(host))
    }

    @Test
    fun anAddressOnTheInternetIsNotLocal() {
        val public = listOf(
            "github.com", "codeberg.org", "f-droid.org", "example.co.uk", "localhost.example.org", "lan.example.org",
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.167.1.1", "169.253.1.1", "11.0.0.1", "223.255.255.255",
            "[2001:db8::1]", "[2606:4700:4700::1111]", "[64:ff9b::808:808]",
            "3com.example.org", "0x.example.org", "1e100.net",
        )
        for (host in public) assertFalse("$host should not count as local", Urls.isLocal(host))
    }
}
