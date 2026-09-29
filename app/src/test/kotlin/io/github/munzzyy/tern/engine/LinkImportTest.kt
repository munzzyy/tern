package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InsecureUrlException
import io.github.munzzyy.tern.core.net.PoliteHttp
import io.github.munzzyy.tern.core.net.RateLimiter
import io.github.munzzyy.tern.engine.real.Links
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The link door behind the same polite client the engine puts in front of its transport. */
class LinkImportTest {
    private val requests = ArrayList<HttpRequest>()
    private var answer: (HttpRequest) -> HttpResponse = { throw IOException("nothing is scripted for ${it.url}") }
    private var now = 1_000_000L
    private val transport = HttpClient { request ->
        requests += request
        answer(request)
    }
    private val links = Links(PoliteHttp(transport, RateLimiter { now }, "Tern/test"), ImportSentences) { now }

    private fun serve(body: ByteArray, status: Int = 200, headers: Headers = Headers.EMPTY) {
        answer = { HttpResponse.of(status, body, headers, it.url) }
    }

    private fun serve(body: String, status: Int = 200) = serve(body.toByteArray(), status)

    private fun padded(export: String, size: Int): ByteArray {
        val bytes = export.toByteArray()
        return bytes + ByteArray(size - bytes.size) { ' '.code.toByte() }
    }

    @Test
    fun plainHttpIsRefusedBeforeAnythingIsSent() {
        serve(exportOf("Wren"))
        for (address in listOf("http://files.example/tern-apps.json", "HTTP://files.example/tern-apps.json", " http://files.example ", "ftp://files.example/tern-apps.json")) {
            refused(ProblemKind.UNSUPPORTED, "linkNotHttps") { links.read(address) }
        }
        assertEquals(emptyList<HttpRequest>(), requests)
    }

    @Test
    fun aRedirectToPlainHttpIsRefusedTheWayTheTransportReportsIt() {
        answer = { throw InsecureUrlException("http://files.example/tern-apps.json (redirected from ${it.url})") }
        refused(ProblemKind.UNSUPPORTED, "linkLeavesHttps") { links.read("https://files.example/latest") }
    }

    @Test
    fun anAnswerThatCameFromPlainHttpIsRefusedEvenWhenTheTransportFollowedTheRedirect() {
        answer = { HttpResponse.of(200, exportOf("Wren"), Headers.EMPTY, "http://files.example/tern-apps.json") }
        refused(ProblemKind.UNSUPPORTED, "linkLeavesHttps") { links.read("https://files.example/latest") }
    }

    @Test
    fun anAnswerFromAnotherHttpsAddressIsTaken() {
        answer = { HttpResponse.of(200, exportOf("Wren"), Headers.EMPTY, "https://mirror.example/tern-apps.json") }
        assertEquals(listOf("Wren"), links.read("https://files.example/latest").apps.map { it.name })
    }

    @Test
    fun twoMebibytesAreReadAndOneByteMoreIsRefused() {
        serve(padded(exportOf("Wren", "Dunnock"), TWO_MEBIBYTES))
        assertEquals(listOf("Wren", "Dunnock"), links.read("https://files.example/tern-apps.json").apps.map { it.name })

        serve(padded(exportOf("Wren", "Dunnock"), TWO_MEBIBYTES + 1))
        refused(ProblemKind.UNSUPPORTED, "importTooLarge($TWO_MEBIBYTES)") { links.read("https://files.example/tern-apps.json") }
    }

    @Test
    fun aFileAnnouncedAsTooLargeIsRefusedWithoutReadingIt() {
        val unread = object : InputStream() {
            override fun read(): Int = throw AssertionError("the body was read")
        }
        answer = { HttpResponse(200, Headers.of("Content-Length" to "${TWO_MEBIBYTES + 1}"), unread, it.url) }
        refused(ProblemKind.UNSUPPORTED, "importTooLarge($TWO_MEBIBYTES)") { links.read("https://files.example/tern-apps.json") }

        answer = { HttpResponse.of(200, exportOf("Wren"), Headers.of("Content-Length" to "$TWO_MEBIBYTES"), it.url) }
        assertEquals(listOf("Wren"), links.read("https://files.example/tern-apps.json").apps.map { it.name })
    }

    @Test
    fun aMissingFileSaysSo() {
        serve("not here", status = 404)
        refused(ProblemKind.NOT_FOUND, "linkNotFound") { links.read("https://files.example/tern-apps.json") }
        serve("gone", status = 410)
        refused(ProblemKind.NOT_FOUND, "linkNotFound") { links.read("https://files.example/tern-apps.json") }
    }

    @Test
    fun aServerThatWantsALoginOrFailsSaysSo() {
        serve("log in first", status = 401)
        refused(ProblemKind.AUTH, "linkRefused(401)") { links.read("https://files.example/tern-apps.json") }
        serve("not for you", status = 403)
        refused(ProblemKind.AUTH, "linkRefused(403)") { links.read("https://files.example/tern-apps.json") }
        serve(exportOf("Wren"), status = 500)
        refused(ProblemKind.NETWORK, "serverStatus(500)") { links.read("https://files.example/tern-apps.json") }
        serve(exportOf("Wren"), status = 302)
        refused(ProblemKind.NETWORK, "serverStatus(302)") { links.read("https://files.example/tern-apps.json") }
    }

    @Test
    fun aBodyThatIsNotAnExportIsRefused() {
        for (body in listOf("<!DOCTYPE html><html><body>tern-apps.json</body></html>", """{"message":"Not Found"}""", "", "7")) {
            serve(body)
            refused(ProblemKind.PARSE, "linkNotAnExport") { links.read("https://files.example/tern-apps.json") }
        }
    }

    @Test
    fun anExportIsRead() {
        serve(exportOf("Wren", "Dunnock"))
        val tern = links.read("https://files.example/tern-apps.json")
        assertEquals(listOf("https://github.com/example/wren", "https://github.com/example/dunnock"), tern.apps.map { it.source.url })
        assertEquals(emptyList<Pair<String, String>>(), tern.skipped)

        serve("""{"apps":[{"id":"org.example.wren","url":"https://github.com/example/wren","name":"Wren"},{"url":"https://apkpure.com/wren","name":"Copy","overrideSource":"APKPure"}]}""")
        val obtainium = links.read("https://files.example/obtainium.json")
        assertEquals(listOf("Wren"), obtainium.apps.map { it.name })
        assertEquals(listOf("Copy"), obtainium.skipped.map { it.first })
    }

    @Test
    fun theRequestCarriesNoTokenAndGoesToTheAddressAsTyped() {
        serve(exportOf("Wren"))
        links.read("  https://Files.Example/lists/tern-apps.json?raw=1  ")
        val sent = requests.single()
        assertEquals("https://files.example/lists/tern-apps.json?raw=1", sent.url)
        assertEquals("GET", sent.method)
        assertNull(sent.authorization)
        assertEquals(setOf("accept", "user-agent"), sent.headers.keys.mapTo(HashSet()) { it.lowercase() })
    }

    @Test
    fun anAddressWithoutASchemeIsAskedForOverHttps() {
        serve(exportOf("Wren"))
        links.read("files.example:8443/tern-apps.json")
        assertEquals("https://files.example:8443/tern-apps.json", requests.single().url)
    }

    @Test
    fun whatIsNoAddressIsRefusedBeforeAnythingIsSent() {
        serve(exportOf("Wren"))
        val tooLong = "https://files.example/" + "a".repeat(2048)
        for (address in listOf("", "   ", "https://", "https://user:secret@files.example/tern-apps.json", "two words", tooLong)) {
            refused(ProblemKind.UNSUPPORTED, "linkNotAnAddress") { links.read(address) }
        }
        assertEquals(emptyList<HttpRequest>(), requests)
    }

    @Test
    fun aServerThatCannotBeReachedSaysWhy() {
        answer = { throw IOException("Connection refused") }
        refused(ProblemKind.NETWORK, "linkUnreachable(Connection refused)") { links.read("https://files.example/tern-apps.json") }
    }

    @Test
    fun aConnectionThatBreaksInTheBodySaysWhy() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")
        }
        answer = { HttpResponse(200, Headers.EMPTY, broken, it.url) }
        refused(ProblemKind.NETWORK, "linkUnreachable(Connection reset)") { links.read("https://files.example/tern-apps.json") }
    }

    @Test
    fun aServerThatAsksToWaitIsNotAskedAgainBeforeThat() {
        answer = { HttpResponse.of(429, "slow down", Headers.of("Retry-After" to "120"), it.url) }
        try {
            links.read("https://files.example/tern-apps.json")
            fail("was not refused")
        } catch (e: ProblemException) {
            assertEquals(Problem(ProblemKind.RATE_LIMITED, "checkRateLimited(${now + 120_000})", now + 120_000), e.problem)
        }
        serve(exportOf("Wren"))
        refused(ProblemKind.RATE_LIMITED, "checkRateLimited(${now + 120_000})") { links.read("https://files.example/tern-apps.json") }
        assertEquals(1, requests.size)
    }

    @Test
    fun aBodyThatTakesLongerThanAMinuteIsGivenUp() {
        val export = exportOf("Wren").toByteArray()
        var sent = 0
        val dripping = object : InputStream() {
            override fun read(): Int {
                now += 20_000
                return if (sent < export.size) export[sent++].toInt() and 0xff else -1
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val byte = read()
                if (byte < 0) return -1
                buffer[offset] = byte.toByte()
                return 1
            }
        }
        answer = { HttpResponse(200, Headers.EMPTY, dripping, it.url) }
        refused(ProblemKind.NETWORK, "linkTooSlow") { links.read("https://files.example/tern-apps.json") }
        assertTrue("read $sent bytes", sent in 1..4)
    }

    private companion object {
        const val TWO_MEBIBYTES = 2 * 1024 * 1024
    }
}
