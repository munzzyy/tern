package io.github.munzzyy.stamp.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.InsecureUrlException
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.real.Texts
import io.github.munzzyy.stamp.enginetest.LoopbackServer.Companion.head
import io.github.munzzyy.stamp.install.Downloader
import io.github.munzzyy.stamp.install.StepFailure
import io.github.munzzyy.stamp.net.TooManyRedirectsException
import io.github.munzzyy.stamp.net.UrlConnectionHttp
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetTest {
    private val dir = File(targetContext.cacheDir, "nettest").apply { deleteRecursively() }
    private val local = UrlConnectionHttp(cleartextHostsForTests = setOf("127.0.0.1"))

    @Test
    fun aCutDownloadResumesToTheIdenticalFile() = runBlocking {
        val content = Random(7).nextBytes(300_000)
        var calls = 0
        LoopbackServer { request, out ->
            if (calls++ == 0) {
                head(out, "200 OK", "Content-Length" to "${content.size}", "ETag" to "\"e1\"")
                out.write(content, 0, 100_000)
                out.flush()
                throw IOException("connection cut on purpose")
            }
            val from = request.header("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
            head(out, "206 Partial Content", "Content-Range" to "bytes $from-${content.size - 1}/${content.size}", "Content-Length" to "${content.size - from}", "ETag" to "\"e1\"")
            out.write(content, from, content.size - from)
        }.use { server ->
            val downloader = Downloader(local, dir, Texts(targetContext))
            val url = "http://127.0.0.1:${server.port}/app.apk"
            try {
                downloader.fetch("resume", url, null) { _, _ -> }
                fail("the cut connection was not noticed")
            } catch (e: StepFailure) {
                assertEquals(ProblemKind.NETWORK, e.kind)
            }
            val kept = downloader.folder("resume").listFiles().orEmpty().single { it.name.endsWith(".part") }.length()
            assertTrue("partial file holds $kept bytes", kept in 1..299_999)

            val result = downloader.fetch("resume", url, null) { _, _ -> }
            assertEquals(Fingerprints.sha256(content), result.sha256)
            assertArrayEquals(content, result.file.readBytes())
            val second = server.requests[1]
            assertEquals("bytes=$kept-", second.header("Range"))
            assertEquals("\"e1\"", second.header("If-Range"))
            assertTrue(server.requests.all { it.header("Accept-Encoding") == "identity" })
        }
    }

    @Test
    fun aChangedETagRestartsFromZero() = runBlocking {
        val first = Random(1).nextBytes(200_000)
        val second = Random(2).nextBytes(150_000)
        var calls = 0
        LoopbackServer { request, out ->
            if (calls++ == 0) {
                head(out, "200 OK", "Content-Length" to "${first.size}", "ETag" to "\"e1\"")
                out.write(first, 0, 50_000)
                out.flush()
                throw IOException("connection cut on purpose")
            }
            if (request.header("If-Range") != "\"e2\"") {
                head(out, "200 OK", "Content-Length" to "${second.size}", "ETag" to "\"e2\"")
                out.write(second)
            } else {
                head(out, "206 Partial Content", "Content-Range" to "bytes 50000-${first.size - 1}/${first.size}", "Content-Length" to "${first.size - 50_000}")
                out.write(first, 50_000, first.size - 50_000)
            }
        }.use { server ->
            val downloader = Downloader(local, dir, Texts(targetContext))
            val url = "http://127.0.0.1:${server.port}/app.apk"
            runCatching { downloader.fetch("etag", url, null) { _, _ -> } }
            val result = downloader.fetch("etag", url, null) { _, _ -> }
            assertEquals("\"e1\"", server.requests[1].header("If-Range"))
            assertEquals(Fingerprints.sha256(second), result.sha256)
            assertArrayEquals(second, result.file.readBytes())
        }
    }

    @Test
    fun aServerThatNamesNoLengthCannotFillTheDevice() = runBlocking {
        val block = ByteArray(1024 * 1024) { 7 }
        LoopbackServer { _, out ->
            head(out, "200 OK")
            repeat(12) { out.write(block) }
        }.use { server ->
            var looks = 0
            val downloader = Downloader(local, dir, Texts(targetContext), freeBytes = { if (looks++ == 0) Long.MAX_VALUE else 1024L * 1024 })
            try {
                downloader.fetch("endless", "http://127.0.0.1:${server.port}/app.apk", null) { _, _ -> }
                fail("twelve megabytes were written with one megabyte free")
            } catch (e: StepFailure) {
                assertEquals(ProblemKind.STORAGE, e.kind)
            }
            assertTrue("free space was looked at $looks times", looks >= 2)
            assertEquals(emptyList<String>(), downloader.folder("endless").listFiles().orEmpty().map { it.name })
        }
    }

    @Test
    fun aServerThatNamesNoLengthIsTakenWhereThereIsRoom() = runBlocking {
        val content = Random(11).nextBytes(9 * 1024 * 1024)
        LoopbackServer { _, out ->
            head(out, "200 OK")
            out.write(content)
        }.use { server ->
            val downloader = Downloader(local, dir, Texts(targetContext), freeBytes = { 10L * 1024 * 1024 * 1024 })
            val result = downloader.fetch("roomy", "http://127.0.0.1:${server.port}/app.apk", null) { _, _ -> }
            assertEquals(Fingerprints.sha256(content), result.sha256)
            assertEquals(content.size.toLong(), result.size)
        }
    }

    @Test
    fun aDownloadThereIsNoRoomForLeavesNothingBehind() = runBlocking {
        val content = Random(13).nextBytes(300_000)
        var calls = 0
        LoopbackServer { request, out ->
            if (calls++ == 0) {
                head(out, "200 OK", "Content-Length" to "${content.size}", "ETag" to "\"e1\"")
                out.write(content, 0, 100_000)
                out.flush()
                throw IOException("cut on purpose")
            }
            val from = request.header("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
            head(out, "206 Partial Content", "Content-Range" to "bytes $from-${content.size - 1}/${content.size}", "Content-Length" to "${content.size - from}", "ETag" to "\"e1\"")
            out.write(content, from, content.size - from)
        }.use { server ->
            var free = Long.MAX_VALUE
            val downloader = Downloader(local, dir, Texts(targetContext), freeBytes = { free })
            val url = "http://127.0.0.1:${server.port}/app.apk"
            runCatching { downloader.fetch("full", url, null) { _, _ -> } }
            assertTrue(downloader.folder("full").listFiles().orEmpty().any { it.name.endsWith(".part") })

            free = 1024
            try {
                downloader.fetch("full", url, null) { _, _ -> }
                fail("a download was carried on with a kilobyte free")
            } catch (e: StepFailure) {
                assertEquals(ProblemKind.STORAGE, e.kind)
            }
            assertEquals(emptyList<String>(), downloader.folder("full").listFiles().orEmpty().map { it.name })
        }
    }

    @Test
    fun theTokenStaysOnItsOwnHostThroughRedirects() {
        LoopbackServer { request, out ->
            when (request.target) {
                "http://10.0.2.2/start" -> head(out, "302 Found", "Location" to "/same")
                "http://10.0.2.2/same" -> head(out, "302 Found", "Location" to "http://127.0.0.1/other")
                "http://127.0.0.1/other" -> head(out, "302 Found", "Location" to "http://10.0.2.2/back")
                "http://10.0.2.2/back" -> {
                    head(out, "200 OK", "Content-Length" to "2")
                    out.write("ok".toByteArray())
                }
                else -> head(out, "404 Not Found", "Content-Length" to "0")
            }
        }.use { proxy ->
            val http = UrlConnectionHttp(
                proxy = { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)) },
                cleartextHostsForTests = setOf("10.0.2.2", "127.0.0.1"),
            )
            val text = http.execute(HttpRequest("http://10.0.2.2/start", authorization = "Bearer s3cret")).text()
            assertEquals("ok", text)
            val seen = proxy.requests.associate { it.target to it.header("Authorization") }
            assertEquals(listOf("http://10.0.2.2/start", "http://10.0.2.2/same", "http://127.0.0.1/other", "http://10.0.2.2/back"), proxy.requests.map { it.target })
            assertEquals("Bearer s3cret", seen["http://10.0.2.2/start"])
            assertEquals("Bearer s3cret", seen["http://10.0.2.2/same"])
            assertNull(seen["http://127.0.0.1/other"])
            assertNull(seen["http://10.0.2.2/back"])
        }
    }

    @Test
    fun aRedirectToCleartextIsRefused() {
        LoopbackServer { _, out -> head(out, "302 Found", "Location" to "http://example.org/app.apk") }.use { server ->
            try {
                local.execute(HttpRequest("http://127.0.0.1:${server.port}/start", authorization = "Bearer s3cret")).close()
                fail("followed a redirect to http")
            } catch (e: InsecureUrlException) {
                assertTrue(e.url.startsWith("http://example.org/"))
            }
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun withoutTheTestFlagEvenLoopbackCleartextIsRefused() {
        LoopbackServer { _, out -> head(out, "200 OK", "Content-Length" to "0") }.use { server ->
            try {
                UrlConnectionHttp().execute(HttpRequest("http://127.0.0.1:${server.port}/")).close()
                fail("sent a cleartext request")
            } catch (_: InsecureUrlException) {
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    @Test
    fun aRedirectLoopStopsAfterEightHops() {
        LoopbackServer { request, out -> head(out, "302 Found", "Location" to "${request.target}x") }.use { server ->
            try {
                local.execute(HttpRequest("http://127.0.0.1:${server.port}/a")).close()
                fail("followed a redirect loop")
            } catch (_: TooManyRedirectsException) {
                assertEquals(9, server.requests.size)
            }
        }
    }
}
