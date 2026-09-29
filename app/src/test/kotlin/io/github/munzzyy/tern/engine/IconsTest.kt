package io.github.munzzyy.tern.engine

import com.sun.net.httpserver.HttpServer
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.PoliteHttp
import io.github.munzzyy.tern.core.net.RateLimiter
import io.github.munzzyy.tern.engine.real.Icons
import io.github.munzzyy.tern.net.UrlConnectionHttp
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IconsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val served = Served()
    private val decoded = AtomicInteger()
    private val dir: File by lazy { File(folder.root, "icons") }
    private val icons: Icons by lazy { Icons(served, dir) { now } }

    private val address = "https://forge.test/avatars/one"
    private val picture = Pictures.png(96, 96)

    private class Served : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val answers = HashMap<String, (HttpRequest) -> HttpResponse>()

        fun picture(url: String, bytes: ByteArray, type: String = "image/png", declare: Boolean = true) {
            answers[url] = {
                val headers = listOfNotNull("Content-Type" to type, if (declare) "Content-Length" to bytes.size.toString() else null)
                HttpResponse.of(200, bytes, Headers.of(headers), url)
            }
        }

        fun status(url: String, status: Int) {
            answers[url] = { HttpResponse.of(status, "no", url = url) }
        }

        fun to(url: String): Int = requests.count { it.url == url }

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return answers[request.url]?.invoke(request) ?: throw IOException("Nothing is served at ${request.url}")
        }
    }

    private fun load(vararg addresses: String, mayFetch: Boolean = true, using: Icons = icons): ByteArray? = runBlocking {
        using.load(addresses.toList(), mayFetch) {
            decoded.incrementAndGet()
            it
        }
    }

    private fun kept(): List<File> = dir.listFiles().orEmpty().filter { it.name.endsWith(Icons.PICTURE) }

    /** Not shown, not kept, never handed to the decoder, and not asked for again on the next draw. */
    private fun assertRefused(url: String = address) {
        assertNull(load(url))
        assertEquals(0, decoded.get())
        assertTrue(kept().isEmpty())
        assertNull(load(url))
        assertEquals(1, served.to(url))
    }

    @Test
    fun aPictureIsFetchedOnceAndComesFromTheFolderAfterThat() {
        served.picture(address, picture)
        assertArrayEquals(picture, load(address))
        assertArrayEquals(picture, load(address))
        assertArrayEquals(picture, load(address, using = Icons(served, dir) { now }))
        assertEquals(1, served.to(address))
        assertEquals(1, kept().size)
    }

    @Test
    fun aFileThatIsTooLargeIsNotShown() {
        val large = Pictures.png(96, 96, padding = Icons.MAX_BYTES)
        assertTrue(large.size > Icons.MAX_BYTES)
        served.picture(address, large, declare = false)
        assertRefused()
    }

    @Test
    fun aFileThatSaysItIsTooLargeIsNotEvenRead() {
        val read = AtomicInteger()
        val body = object : ByteArrayInputStream(picture) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read.incrementAndGet()
                return super.read(b, off, len)
            }
        }
        served.answers[address] = { HttpResponse(200, Headers.of("Content-Length" to (Icons.MAX_BYTES + 1).toString()), body, address) }
        assertRefused()
        assertEquals(0, read.get())
    }

    @Test
    fun theLargestFileAllowedIsShown() {
        val padding = Icons.MAX_BYTES - Pictures.png(96, 96).size
        val largest = Pictures.png(96, 96, padding = padding)
        assertEquals(Icons.MAX_BYTES, largest.size)
        served.picture(address, largest, declare = false)
        assertArrayEquals(largest, load(address))
    }

    @Test
    fun aFileThatLiesAboutItsTypeIsNotShown() {
        val lies = listOf(
            "<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray(),
            "GIF89a".toByteArray() + ByteArray(64),
            "BM".toByteArray() + ByteArray(64),
            ByteArray(0),
        )
        lies.forEachIndexed { index, bytes ->
            val url = "$address/$index"
            served.picture(url, bytes, type = "image/png")
            assertRefused(url)
        }
    }

    @Test
    fun aTruncatedPngIsNotShown() {
        for (length in listOf(picture.size - 1, picture.size - 12, picture.size / 2, 40, 8)) {
            val url = "$address/$length"
            served.picture(url, picture.copyOf(length), declare = false)
            assertRefused(url)
        }
    }

    @Test
    fun aPngOfFiveThousandByFiveThousandIsNotShown() {
        served.picture(address, Pictures.png(5000, 5000))
        assertRefused()
    }

    @Test
    fun thePictureMayBeAsWideAndHighAsTheLimitAndNoMore() {
        served.picture("$address/edge", Pictures.png(Icons.MAX_SIDE, Icons.MAX_SIDE))
        served.picture("$address/wide", Pictures.png(Icons.MAX_SIDE + 1, 8))
        served.picture("$address/high", Pictures.png(8, Icons.MAX_SIDE + 1))
        assertNotNull(load("$address/edge"))
        decoded.set(0)
        dir.deleteRecursively()
        assertRefused("$address/wide")
        assertRefused("$address/high")
    }

    @Test
    fun anSvgIsNeverDecoded() {
        served.picture("$address/honest", Pictures.SVG.toByteArray(), type = "image/svg+xml")
        served.picture("$address/lying", Pictures.SVG.toByteArray(), type = "image/png")
        assertRefused("$address/honest")
        assertRefused("$address/lying")
    }

    @Test
    fun aRedirectToPlainHttpIsNotFollowed() {
        val asked = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.path
            if (exchange.requestURI.path == "/icon.png") {
                exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/plain.png")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, picture.size.toLong())
                exchange.responseBody.write(picture)
            }
            exchange.close()
        }
        server.start()
        try {
            val onlyTheFirstHost = Icons(UrlConnectionHttp(cleartextHostsForTests = setOf("127.0.0.1")), dir) { now }
            val url = "http://127.0.0.1:${server.address.port}/icon.png"
            assertNull(load(url, using = onlyTheFirstHost))
            assertNull(load(url, using = onlyTheFirstHost))
            assertEquals(listOf("/icon.png"), asked.toList())
            assertEquals(0, decoded.get())
            assertTrue(kept().isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anAddressThatIsNotHttpsNeverReachesTheNetwork() {
        served.picture("http://forge.test/avatars/one", picture)
        val polite = Icons(PoliteHttp(served, RateLimiter { now }, "Tern/test"), dir) { now }
        assertNull(load("http://forge.test/avatars/one", using = polite))
        assertTrue(served.requests.isEmpty())
    }

    @Test
    fun theRequestCarriesNoTokenNoCookieAndNoReferrer() {
        served.picture(address, picture)
        val polite = Icons(PoliteHttp(served, RateLimiter { now }, "Tern/test"), dir) { now }
        assertNotNull(load(address, using = polite))
        val request = served.requests.single()
        assertNull(request.authorization)
        assertEquals("GET", request.method)
        assertEquals(setOf("accept", "accept-encoding", "user-agent"), request.headers.keys.map { it.lowercase() }.toSet())
        assertEquals("Tern/test", request.headers["User-Agent"])
    }

    @Test
    fun aWrongAnswerIsRememberedForADay() {
        served.status(address, 404)
        assertNull(load(address))
        now += Icons.DAY_MS - 1
        assertNull(load(address))
        assertEquals(1, served.to(address))

        served.picture(address, picture)
        now += 2
        assertArrayEquals(picture, load(address))
        assertEquals(2, served.to(address))
    }

    @Test
    fun noAnswerAtAllIsAskedForAgainSooner() {
        val failures = listOf<(HttpRequest) -> HttpResponse>(
            { throw SocketTimeoutException("timed out") },
            { HttpResponse.of(503, "busy", url = it.url) },
            { HttpResponse.of(429, "slow down", url = it.url) },
        )
        failures.forEachIndexed { index, failure ->
            val url = "$address/$index"
            served.answers[url] = failure
            assertNull(load(url))
            now += Icons.SOON_MS - 1
            assertNull(load(url))
            assertEquals(1, served.to(url))

            served.picture(url, picture)
            now += 2
            assertArrayEquals(picture, load(url))
        }
    }

    @Test
    fun aTransportThatThrowsSomethingElseDoesNotCrashTheDraw() {
        served.answers[address] = { throw IllegalArgumentException("unexpected") }
        assertRefused()
    }

    @Test
    fun aBodyThatTricklesInIsGivenUp() {
        val reads = AtomicInteger()
        val trickle = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads.incrementAndGet()
                now += 1_000
                b[off] = 0
                return 1
            }
        }
        served.answers[address] = { HttpResponse(200, Headers.EMPTY, trickle, address) }
        assertNull(load(address))
        assertTrue("stopped after ${reads.get()} reads", reads.get() <= Icons.FETCH_MS / 1_000 + 1)
        assertTrue(kept().isEmpty())
    }

    @Test
    fun aKeptPictureIsAskedForAgainAfterSevenDays() {
        served.picture(address, picture)
        assertArrayEquals(picture, load(address))
        now += Icons.REFRESH_MS
        assertArrayEquals(picture, load(address))
        assertEquals(1, served.to(address))

        val newer = Pictures.png(96, 96, seed = 7)
        served.picture(address, newer)
        now += 1
        assertArrayEquals(newer, load(address))
        assertEquals(2, served.to(address))
        assertEquals(1, kept().size)
    }

    @Test
    fun theOldPictureStaysWhenItCannotBeRefreshed() {
        served.picture(address, picture)
        load(address)
        now += Icons.REFRESH_MS + 1
        for (failure in listOf<(HttpRequest) -> HttpResponse>({ throw IOException("down") }, { HttpResponse.of(404, "gone", url = it.url) }, { HttpResponse.of(200, Pictures.SVG, url = it.url) })) {
            served.answers[address] = failure
            val before = served.to(address)
            assertArrayEquals(picture, load(address))
            assertArrayEquals(picture, load(address))
            assertEquals("asked once, then left alone", before + 1, served.to(address))
            now += Icons.DAY_MS + 1
        }
    }

    @Test
    fun theAddressesAreTriedInTheirOrder() {
        val first = "https://forge.test/store/icon.png"
        val second = "https://forge.test/avatars/owner"
        val other = Pictures.png(64, 64, seed = 3)
        served.status(first, 404)
        served.picture(second, other)
        assertArrayEquals(other, load(first, second))
        assertEquals(listOf(first, second), served.requests.map { it.url })

        assertArrayEquals(other, load(first, second))
        assertEquals("the miss and the hit are both remembered", 2, served.requests.size)

        served.picture(first, picture)
        now += Icons.DAY_MS + 1
        assertArrayEquals(picture, load(first, second))
    }

    @Test
    fun noMoreAddressesAreTriedThanAListingMayName() {
        val many = List(10) { "$address/$it" }
        assertNull(load(*many.toTypedArray()))
        assertEquals(many.take(4), served.requests.map { it.url })
    }

    @Test
    fun nothingIsAskedForWhileTheDeviceIsOffline() {
        served.picture(address, picture)
        assertNull(load(address, mayFetch = false))
        assertTrue(served.requests.isEmpty())

        assertArrayEquals(picture, load(address))
        now += Icons.REFRESH_MS * 3
        assertArrayEquals("what is kept is shown however old", picture, load(address, mayFetch = false))
        assertEquals(1, served.requests.size)
    }

    @Test
    fun aPictureTheDecoderRefusesIsDroppedAndNotAskedForAgain() {
        served.picture(address, picture)
        val refusing: (ByteArray) -> ByteArray? = { null }
        assertNull(runBlocking { icons.load(listOf(address), true, refusing) })
        assertTrue(kept().isEmpty())
        assertNull(load(address))
        assertEquals(1, served.to(address))
    }

    @Test
    fun aKeptFileThatWasDamagedIsDroppedAndFetchedAgain() {
        served.picture(address, picture)
        load(address)
        val file = kept().single()
        file.writeBytes(picture.copyOf(picture.size - 20))
        file.setLastModified(now)

        assertArrayEquals(picture, load(address))
        assertEquals(2, served.to(address))
        assertArrayEquals(picture, kept().single().readBytes())
    }

    @Test
    fun theFolderStaysUnderItsLimitAndTheOldestGoFirst() {
        val each = Icons.MAX_BYTES - 1024
        val count = (Icons.MAX_TOTAL_BYTES / each).toInt() + 6
        val pictures = List(count) { Pictures.png(96, 96, padding = each - 200, seed = it) }
        pictures.forEachIndexed { index, bytes ->
            served.picture("$address/$index", bytes)
            now += 1_000
            assertArrayEquals(bytes, load("$address/$index"))
            assertTrue(dir.listFiles().orEmpty().sumOf { it.length() } <= Icons.MAX_TOTAL_BYTES)
        }
        assertTrue(kept().size < count)

        assertArrayEquals(pictures.last(), load("$address/${count - 1}"))
        assertEquals("the newest is still kept", 1, served.to("$address/${count - 1}"))
        assertArrayEquals(pictures.first(), load("$address/0"))
        assertEquals("the oldest had to go", 2, served.to("$address/0"))
    }

    @Test
    fun aRefusalWrittenByAClockThatWasWrongDoesNotLastForever() {
        served.status(address, 404)
        now += 400L * Icons.DAY_MS
        assertNull(load(address))
        now -= 400L * Icons.DAY_MS
        served.picture(address, picture)
        assertArrayEquals(picture, load(address))
    }

    @Test
    fun theDecoderIsAskedForNoMoreThanItNeeds() {
        assertEquals(1, Icons.sampleFor(96, 96))
        assertEquals(1, Icons.sampleFor(191, 96))
        assertEquals(2, Icons.sampleFor(192, 96))
        assertEquals(4, Icons.sampleFor(512, 96))
        assertEquals(16, Icons.sampleFor(2048, 96))
        assertEquals(1, Icons.sampleFor(48, 96))
        assertEquals(1, Icons.sampleFor(512, 0))
        assertFalse(Icons.sampleFor(Int.MAX_VALUE, 1) > 64)
    }
}
