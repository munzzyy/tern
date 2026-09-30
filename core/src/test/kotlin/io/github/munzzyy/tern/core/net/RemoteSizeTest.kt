package io.github.munzzyy.tern.core.net

import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSizeTest {
    private val url = "https://files.example.org/app.apk"

    @Test
    fun oneByteIsAskedForAndTheWholeLengthIsRead() {
        val http = FakeHttp().bytes(url, ByteArray(4096))
        assertEquals(4096L, RemoteSize.of(http, url))
        val request = http.requestsTo(url).single()
        assertEquals("bytes=0-0", request.headers["Range"])
        assertEquals("GET", request.method)
    }

    @Test
    fun aServerThatSendsTheWholeFileSaysItsLength() {
        assertEquals(1234L, RemoteSize.sizeFrom(200, Headers.of("Content-Length" to "1234")))
        assertEquals(1234L, RemoteSize.sizeFrom(206, Headers.of("Content-Range" to "bytes 0-0/1234", "Content-Length" to "1")))
    }

    @Test
    fun anAnswerThatDoesNotSayGivesNoSize() {
        assertNull(RemoteSize.sizeFrom(206, Headers.of("Content-Range" to "bytes 0-0/*")))
        assertNull(RemoteSize.sizeFrom(206, Headers.of("Content-Length" to "1")))
        assertNull(RemoteSize.sizeFrom(404, Headers.of("Content-Length" to "1234")))
        assertNull(RemoteSize.sizeFrom(200, Headers.of("Content-Length" to "0")))
        assertNull(RemoteSize.sizeFrom(200, Headers.EMPTY))
    }

    @Test
    fun theAuthorizationIsHandedOnAsGiven() {
        val http = FakeHttp().bytes(url, ByteArray(10))
        RemoteSize.of(http, url, authorization = "Bearer tok")
        assertEquals("Bearer tok", http.requestsTo(url).single().authorization)
        RemoteSize.of(http, url)
        assertNull(http.requestsTo(url).last().authorization)
    }
}
