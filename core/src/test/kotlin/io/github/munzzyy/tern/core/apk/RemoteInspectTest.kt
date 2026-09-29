package io.github.munzzyy.tern.core.apk

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInspectTest {
    private val url = "https://example.com/releases/app.apk"

    /** Refuses suffix ranges with 501, as GitHub's release storage does. */
    private fun blobHost(bytes: ByteArray): FakeHttp {
        val honest = FakeHttp().bytes(url, bytes, Headers.of("ETag" to "\"fixture\""))
        return FakeHttp().on(url) { request ->
            if (request.headers["Range"]!!.startsWith("bytes=-")) HttpResponse.of(501, "", url = url) else honest.execute(request)
        }
    }

    private fun remote(bytes: ByteArray): Pair<ApkInfo, HttpRangeSource> {
        val source = HttpRangeSource(blobHost(bytes), url)
        return ApkInspector.inspect(source) to source
    }

    @Test
    fun everyFixtureInspectsRemotelyWithinBudget() {
        for (name in ApkFixtures.apkNames) {
            for (padding in listOf(0, 3 * 1024 * 1024)) {
                val bytes = if (padding == 0) ApkFixtures.bytes(name) else ApkFixtures.padded(ApkFixtures.bytes(name), padding)
                val local = ApkInspector.inspect(BytesSource(bytes))
                val (info, source) = remote(bytes)
                assertEquals(name, local, info)
                assertTrue("$name took ${source.requestCount} requests", source.requestCount < 8)
                assertTrue("$name fetched ${source.bytesFetched} bytes", source.bytesFetched < 300 * 1024)
                if (padding > 0) assertTrue(bytes.size > 3 * 1024 * 1024)
            }
        }
    }

    @Test
    fun inspectRemoteUsesTheSameChecks() {
        val bytes = ApkFixtures.padded(ApkFixtures.bytes("app-rotated.apk"), 2 * 1024 * 1024)
        val http = blobHost(bytes)
        val info = ApkInspector.inspectRemote(http, url, authorization = "token abc")
        assertEquals(ApkInspector.inspect(BytesSource(bytes)), info)
        assertTrue(http.requests.size < 8)
        assertTrue(http.requests.all { it.authorization == "token abc" })
    }

    @Test
    fun serverIgnoringRangeFailsRemoteInspection() {
        val bytes = ApkFixtures.bytes("app-v1.apk")
        val http = FakeHttp().on(url) { HttpResponse.of(200, bytes, Headers.EMPTY, url) }
        assertThrows(RangeNotSupportedException::class.java) { ApkInspector.inspectRemote(http, url) }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun swappedFileFailsRemoteInspection() {
        val first = ApkFixtures.padded(ApkFixtures.bytes("app-v1.apk"), 1024 * 1024)
        val second = ApkFixtures.padded(ApkFixtures.bytes("app-v2-otherkey.apk"), 1024 * 1024)
        var served = 0
        val http = FakeHttp().on(url) { request ->
            val body = if (served++ == 0) first else second
            val etag = if (body === first) "\"a\"" else "\"b\""
            if (request.headers["If-Range"] != null && request.headers["If-Range"] != etag) {
                HttpResponse.of(200, body, Headers.of("ETag" to etag), url)
            } else {
                FakeHttp().bytes(url, body, Headers.of("ETag" to etag)).execute(request)
            }
        }
        assertThrows(RemoteFileChangedException::class.java) { ApkInspector.inspectRemote(http, url) }
    }
}
