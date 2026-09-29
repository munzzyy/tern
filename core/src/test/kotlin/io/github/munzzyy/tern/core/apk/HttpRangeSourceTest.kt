package io.github.munzzyy.tern.core.apk

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InsecureUrlException
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class HttpRangeSourceTest {
    private val url = "https://example.com/app.apk"
    private val blobUrl = "https://blobs.example.net/signed/app.apk?sig=abc"
    private val file = ByteArray(300_000) { (it * 7 + it / 251).toByte() }
    private val tailStart = file.size - 65536

    private fun range(request: HttpRequest) = request.headers["Range"]!!

    private fun bounds(request: HttpRequest): Pair<Int, Int> =
        range(request).removePrefix("bytes=").split('-').let { (a, b) -> a.toInt() to minOf(b.toInt(), file.size - 1) }

    private fun slice(from: Int, to: Int, total: Int = file.size, body: ByteArray = file, headers: List<Pair<String, String>> = emptyList(), at: String = url) =
        HttpResponse.of(206, body.copyOfRange(from, to + 1), Headers.of(headers + ("Content-Range" to "bytes $from-$to/$total")), at)

    /** Behaves like GitHub's release storage: suffix ranges get 501, explicit ranges work. */
    private fun blobHost(body: ByteArray = file, headers: Headers = Headers.of("ETag" to "\"0x8DF1\"")): FakeHttp {
        val honest = FakeHttp().bytes(url, body, headers)
        return FakeHttp().on(url) { request ->
            if (range(request).startsWith("bytes=-")) HttpResponse.of(501, "", url = url) else honest.execute(request)
        }
    }

    @Test
    fun opensWithExplicitRangesOnlyAndCachesTheTail() {
        val http = blobHost()
        val source = HttpRangeSource(http, url)
        assertEquals(file.size.toLong(), source.size)
        assertEquals(listOf("bytes=0-16383", "bytes=$tailStart-${file.size - 1}"), http.requests.map { range(it) })
        assertTrue(http.requests.none { range(it).startsWith("bytes=-") })
        assertArrayEquals(file.copyOfRange(file.size - 100, file.size), source.read(file.size - 100L, 100))
        assertArrayEquals(file.copyOfRange(100, 200), source.read(100, 100))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun earlierReadsAreRoundedToBlocksAndCached() {
        val http = blobHost()
        val source = HttpRangeSource(http, url)
        assertArrayEquals(file.copyOfRange(20_000, 20_100), source.read(20_000, 100))
        assertEquals("bytes=16384-32767", range(http.requests[2]))
        assertArrayEquals(file.copyOfRange(30_000, 30_100), source.read(30_000, 100))
        assertEquals(3, http.requests.size)
        assertArrayEquals(file.copyOfRange(tailStart - 10, tailStart + 10), source.read(tailStart - 10L, 20))
        assertArrayEquals(file, source.read(0, file.size))
        val asked = http.requests.sumOf { bounds(it).let { (a, b) -> b - a + 1L } }
        assertEquals(asked, source.bytesFetched)
        assertTrue(source.bytesFetched <= file.size)
    }

    @Test
    fun smallFileIsServedFromTheFirstResponse() {
        val small = file.copyOf(1000)
        val http = blobHost(small)
        val source = HttpRangeSource(http, url)
        assertArrayEquals(small, source.read(0, 1000))
        assertThrows(ApkFormatException::class.java) { source.read(990, 11) }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun authorizationTravelsOnlyAsTheRequestField() {
        val http = blobHost()
        HttpRangeSource(http, url, authorization = "Bearer secret").read(20_000, 10)
        for (request in http.requests) {
            assertEquals("Bearer secret", request.authorization)
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun laterRequestsGoToTheFinalUrlWithoutAuthorization() {
        val blob = FakeHttp().bytes(blobUrl, file, Headers.of("ETag" to "\"b\""))
        val http = FakeHttp()
            .on(url) { request -> blob.execute(request.copy(url = blobUrl)) }
            .on(blobUrl) { request -> blob.execute(request) }
        val source = HttpRangeSource(http, url, authorization = "Bearer secret")
        source.read(20_000, 10)
        assertEquals(url, http.requests[0].url)
        assertEquals("Bearer secret", http.requests[0].authorization)
        assertTrue(http.requests.drop(1).all { it.url == blobUrl && it.authorization == null })
    }

    @Test
    fun expiredFinalUrlFallsBackToTheOriginalOnce() {
        val blob = FakeHttp().bytes(blobUrl, file, Headers.of("ETag" to "\"b\""))
        var expired = false
        val http = FakeHttp()
            .on(url) { request -> blob.execute(request.copy(url = blobUrl)) }
            .on(blobUrl) { request -> if (expired) HttpResponse.of(403, "expired", url = blobUrl) else blob.execute(request) }
        val source = HttpRangeSource(http, url, authorization = "Bearer secret")
        expired = true
        assertArrayEquals(file.copyOfRange(20_000, 20_010), source.read(20_000, 10))
        val last = http.requests.takeLast(2)
        assertEquals(listOf(blobUrl, url), last.map { it.url })
        assertEquals("Bearer secret", last[1].authorization)
        assertThrows(IOException::class.java) { source.read(40_000, 10) }
    }

    @Test
    fun serverIgnoringRangeIsRefusedWithoutReadingTheBody() {
        var bytesRead = 0
        var closed = false
        val body = object : InputStream() {
            override fun read(): Int = 0.also { bytesRead++ }

            override fun close() {
                closed = true
            }
        }
        val http = FakeHttp().on(url) { HttpResponse(200, Headers.EMPTY, body, url) }
        assertThrows(RangeNotSupportedException::class.java) { HttpRangeSource(http, url) }
        assertEquals(0, bytesRead)
        assertTrue(closed)
    }

    @Test
    fun notImplementedAndUnsatisfiableMeanNoRangeSupport() {
        for (status in listOf(416, 501)) {
            val http = FakeHttp().on(url) { HttpResponse.of(status, "", url = url) }
            assertThrows(RangeNotSupportedException::class.java) { HttpRangeSource(http, url) }
        }
        val failure = runCatching { HttpRangeSource(FakeHttp().on(url) { HttpResponse.of(500, "") }, url) }.exceptionOrNull()
        assertTrue(failure is IOException && failure !is RangeNotSupportedException)
    }

    @Test
    fun lyingContentRangeIsRefused() {
        val cases = listOf<(HttpRequest) -> HttpResponse>(
            { slice(1, 16384) },
            { HttpResponse.of(206, file.copyOfRange(0, 16384), Headers.EMPTY, url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16384), Headers.of("Content-Range" to "bytes 0-16383/*"), url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16384), Headers.of("Content-Range" to "bytes 16383-0/${file.size}"), url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16383), Headers.of("Content-Range" to "bytes 0-16383/${file.size}"), url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16385), Headers.of("Content-Range" to "bytes 0-16383/${file.size}"), url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16384), Headers.of("Content-Range" to "bytes 0-16383/99999999999999999999"), url) },
            { HttpResponse.of(206, file.copyOfRange(0, 16384), Headers.of("Content-Range" to "bytes 0-16383/16000"), url) },
            { slice(0, 16383, headers = listOf("Content-Encoding" to "gzip")) },
        )
        for ((i, case) in cases.withIndex()) {
            val failure = runCatching { HttpRangeSource(FakeHttp().on(url, case), url) }.exceptionOrNull()
            assertTrue("case $i gave $failure", failure is IOException)
        }
    }

    @Test
    fun laterRangeMustMatchTheRequest() {
        val honest = blobHost()
        var lie = false
        val http = FakeHttp().on(url) { request -> if (lie) slice(32768, 49151) else honest.execute(request) }
        val source = HttpRangeSource(http, url)
        lie = true
        assertThrows(ApkFormatException::class.java) { source.read(20_000, 10) }
    }

    @Test
    fun fileSwappedMidwayIsRefused() {
        var current = file
        var etag = "\"v1\""
        val http = FakeHttp().on(url) { request ->
            val ifRange = request.headers["If-Range"]
            if (ifRange != null && ifRange != etag) {
                HttpResponse.of(200, current, Headers.of("ETag" to etag), url)
            } else {
                val (a, b) = bounds(request)
                slice(a, b, current.size, current, listOf("ETag" to etag))
            }
        }
        val source = HttpRangeSource(http, url)
        assertNull(http.requests[0].headers["If-Range"])
        assertEquals("\"v1\"", http.requests[1].headers["If-Range"])
        current = file.copyOf().also { it[20_000] = 1 }
        etag = "\"v2\""
        assertThrows(RemoteFileChangedException::class.java) { source.read(20_000, 10) }
    }

    @Test
    fun changedSizeOrEtagIsRefusedEvenWithoutIfRangeSupport() {
        var total = file.size
        var etag = "\"v1\""
        val http = FakeHttp().on(url) { request ->
            val (a, b) = bounds(request)
            slice(a, b, total, headers = listOf("ETag" to etag))
        }
        val source = HttpRangeSource(http, url)
        total = file.size + 1
        assertThrows(RemoteFileChangedException::class.java) { source.read(20_000, 10) }
        total = file.size
        etag = "\"v2\""
        assertThrows(RemoteFileChangedException::class.java) { source.read(40_000, 10) }
    }

    @Test
    fun weakEtagFallsBackToLastModified() {
        val http = blobHost(headers = Headers.of("ETag" to "W/\"x\"", "Last-Modified" to "Mon, 28 Sep 2026 10:00:00 GMT"))
        HttpRangeSource(http, url)
        assertEquals("Mon, 28 Sep 2026 10:00:00 GMT", http.requests[1].headers["If-Range"])
    }

    @Test
    fun budgetIsEnforcedBeforeTheRequest() {
        val http = blobHost()
        val source = HttpRangeSource(http, url, budgetBytes = 16384 + 65536 + 16384)
        source.read(20_000, 10)
        assertThrows(InspectionBudgetException::class.java) { source.read(40_000, 10) }
        assertEquals(3, http.requests.size)
    }

    @Test
    fun plainHttpIsRefusedBeforeAnyRequest() {
        val http = FakeHttp().bytes("http://example.com/app.apk", file)
        assertThrows(InsecureUrlException::class.java) { HttpRangeSource(http, "http://example.com/app.apk") }
        assertTrue(http.requests.isEmpty())
    }
}
