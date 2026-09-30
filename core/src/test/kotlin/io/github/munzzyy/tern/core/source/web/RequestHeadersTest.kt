package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestHeadersTest {
    @Test
    fun readsAnObjectOfNamesAndValues() {
        assertEquals(mapOf("Referer" to "https://example.com/", "X-A" to "b"), RequestHeaders.parse("""{"Referer": " https://example.com/ ", "X-A": "b"}"""))
        assertEquals(emptyMap<String, String>(), RequestHeaders.parse(null))
        assertEquals(emptyMap<String, String>(), RequestHeaders.parse(" "))
    }

    @Test
    fun refusesCredentialsAndWhatChangesTheMeaningOfARequest() {
        for (name in listOf("Authorization", "proxy-authorization", "Proxy-Connection", "Cookie", "Host", "Content-Length", "Range", "If-None-Match", "if-modified-since", "Transfer-Encoding", "Accept-Encoding")) {
            assertNotNull(name, RequestHeaders.problem(name, "x"))
        }
        assertNull(RequestHeaders.problem("Referer", "https://downloads.example.org/app/latest"))
        assertEquals("The header User-Agent may not be set", RequestHeaders.problem("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) Chrome/114.0.0.0 Mobile Safari/537.36"))
        assertEquals("The header user-agent may not be set", RequestHeaders.problem("user-agent", "Example/1.0"))
        assertNull(RequestHeaders.problem("Referer", "https://example.com/"))
    }

    @Test
    fun refusesControlCharactersAndWhatAHeaderCannotCarry() {
        assertNotNull(RequestHeaders.problem("X-A", "a\r\nX-B: b"))
        assertNotNull(RequestHeaders.problem("X-A", "tab\there"))
        assertNotNull(RequestHeaders.problem("X-A", "café"))
        assertNotNull(RequestHeaders.problem("X A", "b"))
        assertNotNull(RequestHeaders.problem("X:A", "b"))
        assertNotNull(RequestHeaders.problem("X-\u0000", "b"))
        assertNotNull(RequestHeaders.problem("", "b"))
    }

    @Test
    fun capsHowManyHeadersAndHowLongEachIs() {
        val eight = (1..8).joinToString(",", "{", "}") { "\"X-$it\": \"v\"" }
        assertEquals(8, RequestHeaders.parse(eight).size)
        val nine = (1..9).joinToString(",", "{", "}") { "\"X-$it\": \"v\"" }
        assertEquals(SourceErrorKind.UNSUPPORTED, assertThrows(SourceException::class.java) { RequestHeaders.parse(nine) }.kind)
        assertNull(RequestHeaders.problem("X-Long", "v".repeat(494)))
        assertNotNull(RequestHeaders.problem("X-Long", "v".repeat(495)))
    }

    @Test
    fun refusesWhatIsNotAnObjectOfTexts() {
        for (raw in listOf("[]", "not json", """{"X-A": 1}""", """{"X-A": "a", "x-a": "b"}""")) {
            assertEquals(raw, SourceErrorKind.UNSUPPORTED, assertThrows(SourceException::class.java) { RequestHeaders.parse(raw) }.kind)
        }
    }

    @Test
    fun readsWhatItWrites() {
        val headers = mapOf("X-Note" to "Example \"quoted\" \\ note", "X-A" to "b")
        assertEquals(headers, RequestHeaders.parse(RequestHeaders.write(headers)))
    }
}
