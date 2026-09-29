package io.github.munzzyy.stamp.core.handoff

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a phone that means harm sends, and what it gets for it. Real sockets on the loopback address. */
class HandoffRefusalTest {
    private val handoffs = Handoffs()
    private val limits = Handoffs.QUICK

    @After
    fun closeAll() = handoffs.closeAll()

    private fun Phone.raw(request: String): Reply? = exchange(request.toByteArray(Charsets.ISO_8859_1))

    private fun said(reply: Reply?, notice: Notice): Boolean = reply!!.body.contains(">${notice.text(limits)}</p>")

    private fun otherSecret(secret: String): String = (if (secret[0] == 'a') "b" else "a") + secret.drop(1)

    @Test
    fun aWrongSecretIsAnsweredLikeAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        assertEquals(404, nothing.status)
        val secret = phone.secret
        val wrong = otherSecret(secret)
        val gets = listOf(
            "/$wrong", "/$wrong/links", "/$wrong/file", "/${secret}x", "/$secret/", "/$secret/links/", "/${secret.uppercase()}",
            "/$secret?a=1", "/$secret#a", "/${secret.dropLast(1)}", "//$secret", "/pin", "/$secret/links", "/$secret/file", "/favicon.ico",
            "/a".repeat(2000), "*", secret, "http://${phone.host}/$secret",
        )
        for (path in gets) assertArrayEquals(path, nothing.raw, phone.get(path)!!.raw)

        val link = "links=https%3A%2F%2Fexample.org%2Fapp"
        val posts = listOf("/$wrong/links", "/$wrong/file", "/", "/$secret", "/$secret/pin", "/$secret/links/", "/${secret.uppercase()}/links", "/links")
        for (path in posts) assertArrayEquals(path, nothing.raw, phone.form(path, link)!!.raw)
        for (method in listOf("HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "get", "TRACE")) {
            assertArrayEquals(method, nothing.raw, phone.raw(phone.head(method, "/$secret"))!!.raw)
        }
        assertEquals(0, phone.server.waiting())
        assertTrue(phone.server.isOpen)
    }

    @Test
    fun aPathThatClimbsOrHidesIsAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        val secret = phone.secret
        val paths = listOf(
            "/../$secret", "/$secret/..", "/$secret/../$secret", "/./$secret", "/x/../$secret/links", "/..", "/../../../../etc/passwd",
            "/%2e%2e/$secret", "/$secret/%2e%2e/$secret", "/%2E%2E%2F$secret", "/$secret%2Flinks", "/%${"%02x".format(secret[0].code)}${secret.drop(1)}",
            "/$secret\u0000", "/\u0000$secret", "/$secret\u0000/links", "/$secret%00", "/$secret/links\u0000.html", "/$secret\t", "/$secret\u00e9",
        )
        for (path in paths) {
            assertArrayEquals(path, nothing.raw, phone.get(path)!!.raw)
            assertArrayEquals(path, nothing.raw, phone.form(path, "links=https%3A%2F%2Fexample.org%2Fapp")!!.raw)
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aRequestForAnotherHostIsAnsweredLikeAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        val path = "/${phone.secret}"
        for (host in listOf("rebound.example:${phone.port}", "127.0.0.1", "localhost:${phone.port}", "127.0.0.1:${phone.port + 1}", "127.0.0.1:${phone.port} ,x", "")) {
            assertArrayEquals(host, nothing.raw, phone.raw("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n")!!.raw)
            assertArrayEquals(host, nothing.raw, phone.raw("GET / HTTP/1.1\r\nHost: $host\r\n\r\n")!!.raw)
        }
        assertArrayEquals(nothing.raw, phone.raw("GET $path HTTP/1.1\r\n\r\n")!!.raw)
        assertArrayEquals(nothing.raw, phone.raw("GET $path HTTP/1.0\r\n\r\n")!!.raw)
        assertEquals(400, phone.raw("GET $path HTTP/1.1\r\nHost: ${phone.host}\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(200, phone.raw("GET $path HTTP/1.1\r\nhOST:${phone.host}  \r\n\r\n")!!.status)
    }

    @Test
    fun headersOfEightKibibytesAreReadAndOneByteMoreIsNot() {
        val phone = handoffs.open()
        assertEquals(8 * 1024, limits.headBytes)
        fun headOf(size: Int): String {
            val start = "GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\nX-Fill: "
            return start + "f".repeat(size - start.length - 4) + "\r\n\r\n"
        }
        assertEquals(8192, headOf(8192).length)
        assertEquals(200, phone.raw(headOf(8192))!!.status)
        for (size in listOf(8193, 8194, 8200, 16 * 1024, 300 * 1024)) assertEquals("$size", 431, phone.raw(headOf(size))!!.status)
        val manySmall = "GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\n" + "X-A: b\r\n".repeat(2000) + "\r\n"
        assertEquals(431, phone.raw(manySmall)!!.status)
        assertEquals(431, phone.raw("GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
    }

    @Test
    fun aBodyThatFollowsHeadersOfEightKibibytesIsStillRead() {
        val phone = handoffs.open()
        val body = "links=https%3A%2F%2Fexample.org%2Fapp"
        val start = "POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: ${body.length}\r\nX-Fill: "
        val head = start + "f".repeat(8192 - start.length - 4) + "\r\n\r\n"
        assertTrue(said(phone.raw(head + body), Notice.LINKS_SENT))
        assertEquals(listOf(HandoffItem.Link("https://example.org/app")), phone.server.take())
    }

    @Test
    fun aBodyWithoutALengthIsRefused() {
        val phone = handoffs.open()
        val reply = phone.raw("POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\n\r\nlinks=https%3A%2F%2Fexample.org%2Fapp")!!
        assertEquals(411, reply.status)
        assertEquals(411, phone.raw("POST /pin HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\n\r\npin=${phone.server.pin}")!!.status)
        assertEquals(411, phone.raw("POST /nothing HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aChunkedBodyIsRefused() {
        val phone = handoffs.open()
        val chunk = "links=https%3A%2F%2Fexample.org%2Fapp"
        val chunked = "${chunk.length.toString(16)}\r\n$chunk\r\n0\r\n\r\n"
        val start = "POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\n"
        assertEquals(411, phone.raw(start + "Transfer-Encoding: chunked\r\n\r\n" + chunked)!!.status)
        assertEquals(411, phone.raw(start + "Transfer-Encoding: chunked\r\nContent-Length: ${chunk.length}\r\n\r\n" + chunk)!!.status)
        assertEquals(411, phone.raw(start + "Content-Length: ${chunked.length}\r\nTransfer-Encoding: identity\r\n\r\n" + chunked)!!.status)
        assertEquals(400, phone.raw(start + "Transfer-Encoding: chunked\r\nTransfer-Encoding: identity\r\n\r\n" + chunked)!!.status)
        assertEquals(400, phone.raw("GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aBodyLongerThanItSaysIsRefused() {
        val phone = handoffs.open()
        val said = "links=https%3A%2F%2Fexample.org%2Fapp"
        val more = "%0D%0Ahttps%3A%2F%2Fexample.org%2Fslipped-in"
        val start = "POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: ${said.length}\r\n\r\n"
        assertEquals(400, phone.raw(start + said + more)!!.status)
        assertEquals(400, phone.raw(start + said + "\r\n")!!.status)
        assertEquals(400, phone.raw(start + said + "GET / HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())

        val file = Phone.multipart("apps.json", "{}".toByteArray())
        val fileStart = "POST /${phone.secret}/file HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: multipart/form-data; boundary=${Phone.BOUNDARY}\r\n"
        assertEquals(400, phone.exchange("${fileStart}Content-Length: ${file.size}\r\n\r\n".toByteArray() + file + "more".toByteArray())!!.status)
        assertEquals(400, phone.exchange("${fileStart}Content-Length: ${file.size - 1}\r\n\r\n".toByteArray() + file)!!.status)
        assertEquals(0, phone.server.waiting())
        assertEquals(200, phone.raw(start + said)!!.status)
        assertEquals(1, phone.server.waiting())
    }

    @Test
    fun aBodyShorterThanItSaysIsNotTaken() {
        val phone = handoffs.open()
        val said = "links=https%3A%2F%2Fexample.org%2Fapp"
        val start = "POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: ${said.length + 10}\r\n\r\n"
        phone.connect().use { socket ->
            socket.getOutputStream().write((start + said).toByteArray())
            socket.shutdownOutput()
            assertNull(phone.read(socket))
        }
        val began = System.nanoTime()
        assertEquals(408, phone.raw(start + said)!!.status)
        val waited = (System.nanoTime() - began) / 1_000_000
        assertTrue("$waited ms", waited in limits.bodyMs - 100..limits.bodyMs + 1_500)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aLengthThatIsNoNumberIsRefused() {
        val phone = handoffs.open()
        val body = "links=a"
        for (length in listOf("", "-1", "+7", "7.0", "0x7", "7 7", "seven", "7,7", "07a")) {
            val reply = phone.raw("POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: $length\r\n\r\n$body")!!
            assertEquals(length, 400, reply.status)
        }
        val twice = phone.raw("POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: 7\r\nContent-Length: 7\r\n\r\n$body")!!
        assertEquals(400, twice.status)
        for (huge in listOf("99999999999999999999", "4294967303", "2147483648", "999999999")) {
            val reply = phone.raw("POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: $huge\r\n\r\n$body")!!
            assertTrue(huge, said(reply, Notice.LINKS_TOO_LARGE))
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aBodyWhereNoneBelongsIsRefused() {
        val phone = handoffs.open()
        assertEquals(400, phone.raw("GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 5\r\n\r\nhello")!!.status)
        assertEquals(400, phone.raw("GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\n\r\nhello")!!.status)
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 1\r\n\r\n")!!.status)
        assertEquals(200, phone.raw("GET /${phone.secret} HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 0\r\n\r\n")!!.status)
    }

    @Test
    fun aSecondFilePartIsRefused() {
        val phone = handoffs.open()
        val b = Phone.BOUNDARY
        fun part(name: String, content: String) = "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/json\r\n\r\n$content\r\n"
        val type = "multipart/form-data; boundary=$b"
        val two = phone.post("/${phone.secret}/file", type, (part("one.json", "{}") + part("two.json", "{}") + "--$b--\r\n").toByteArray())!!
        assertEquals(400, two.status)
        assertTrue(two.toString(), said(two, Notice.FILE_UNREADABLE))
        val withField = "--$b\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n" + part("one.json", "{}") + "--$b--\r\n"
        assertTrue(said(phone.post("/${phone.secret}/file", type, withField.toByteArray()), Notice.FILE_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/file", type, (part("one.json", "{}") + "--$b\r\n").toByteArray()), Notice.FILE_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/file", "multipart/form-data; boundary=another", (part("one.json", "{}") + "--$b--\r\n").toByteArray()), Notice.FILE_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/file", "multipart/form-data", (part("one.json", "{}") + "--$b--\r\n").toByteArray()), Notice.FILE_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/file", Forms.TYPE, "file=%7B%7D".toByteArray()), Notice.FILE_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/file", "application/json", "{}".toByteArray()), Notice.FILE_UNREADABLE))
        assertEquals(0, phone.server.waiting())
        assertTrue(said(phone.post("/${phone.secret}/file", type, (part("one.json", "{}") + "--$b--\r\n").toByteArray()), Notice.FILE_SENT))
    }

    @Test
    fun aPinThatIsNotSentAsTheFormSendsItIsNoGuess() {
        val phone = handoffs.open()
        val pin = phone.server.pin
        assertEquals(400, phone.post("/pin", "text/plain", "pin=$pin".toByteArray())!!.status)
        assertEquals(400, phone.post("/pin", "application/json", "{\"pin\":\"$pin\"}".toByteArray())!!.status)
        assertEquals(400, phone.form("/pin", "pin=$pin&pin=$pin")!!.status)
        assertEquals(400, phone.form("/pin", "other=$pin")!!.status)
        assertEquals(400, phone.form("/pin", "pin=" + "1".repeat(100))!!.status)
        assertEquals(400, phone.form("/pin", "")!!.status)
        assertEquals(400, phone.form("/pin", "pin=%zz")!!.status)
        assertTrue(phone.server.isOpen)
        assertEquals(303, phone.pin(pin)!!.status)
    }

    @Test
    fun whatIsNoRequestIsRefused() {
        val phone = handoffs.open()
        val host = "Host: ${phone.host}\r\n"
        val not = listOf(
            "\r\n\r\n", "hello\r\n\r\n", "GET /\r\n\r\n", "GET / HTTP/1.1 more\r\n$host\r\n", "GET  / HTTP/1.1\r\n$host\r\n", " GET / HTTP/1.1\r\n$host\r\n",
            "GET / HTTP/2.0\r\n$host\r\n", "GET / http/1.1\r\n$host\r\n", "GET / HTTP/1.1\r\n${host}no colon here\r\n\r\n",
            "GET / HTTP/1.1\r\n$host folded: value\r\n\r\n", "GET / HTTP/1.1\r\n$host: empty name\r\n\r\n", "GET / HTTP/1.1\r\n${host}X Y: z\r\n\r\n",
            "GET / HTTP/1.1\r\n${host}X-A: b\u0000c\r\n\r\n", "GET / HTTP/1.1\r\n${host}X-A: b\rc\r\n\r\n", "GET / HTTP/1.1\r\n${host}X-A: b\nc\r\n\r\n",
            "GET / HTTP/1.1\r\n${host}X-A: caf\u00e9\r\n\r\n", "GET / HTTP/1.1\r\n${host}X-\u00e9: b\r\n\r\n", "\r\nGET / HTTP/1.1\r\n$host\r\n",
            "\u0016\u0003\u0001\u0002\u0000\u0001\u0000\u0001\u00fc\u0003\u0003\r\n\r\n",
        )
        for (request in not) assertEquals(request.take(60), 400, phone.raw(request)!!.status)
        assertEquals(200, phone.raw("GET / HTTP/1.1\r\n${host}X-A:\r\nX-B: \tb\t \r\nX-B: again\r\n\r\n")!!.status)
        assertEquals(200, phone.raw("GET / HTTP/1.0\r\n$host\r\n")!!.status)
    }

    @Test
    fun noAnswerHoldsWhatThePhoneSent() {
        val phone = handoffs.open()
        val mark = "zq7mark"
        val secret = phone.secret
        val wrong = otherSecret(secret)
        val replies = listOfNotNull(
            phone.get("/$mark-path"),
            phone.get("/$secret?$mark-query=$mark-value"),
            phone.get("/$secret", "X-$mark-name: $mark-value", "User-Agent: $mark-agent", "Referer: http://$mark-referer/", "Cookie: $mark-cookie=1"),
            phone.get("/", "X-$mark-name: $mark-value"),
            phone.raw("$mark-method /$secret HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n"),
            phone.raw("GET /$secret HTTP/1.1\r\nHost: $mark-host\r\n\r\n"),
            phone.raw("GET /$secret $mark-version\r\nHost: ${phone.host}\r\n\r\n"),
            phone.raw("GET /$secret HTTP/1.1\r\nHost: ${phone.host}\r\n$mark-no-colon\r\n\r\n"),
            phone.raw("GET /$secret HTTP/1.1\r\nHost: ${phone.host}\r\nX-Long: $mark" + "f".repeat(9000) + "\r\n\r\n"),
            phone.links("https://example.org/$mark-link"),
            phone.links(*Array(21) { "https://example.org/$mark-many-$it" }),
            phone.links("https://example.org/$mark-long" + "a".repeat(2000)),
            phone.links("https://example.org/$mark-hidden\u202e"),
            phone.form("/$secret/links", "$mark-field=$mark-value"),
            phone.form("/$wrong/links", "links=$mark-wrong-secret"),
            phone.post("/$secret/links", "text/$mark-type", "links=$mark-typed".toByteArray()),
            phone.file("$mark-name.json", "{\"$mark-content\":1}".toByteArray()),
            phone.file("$mark-large.json", ByteArray(limits.fileBytes + 1) { 'q'.code.toByte() } + "$mark-tail".toByteArray()),
            phone.post("/$secret/file", "multipart/form-data; boundary=$mark-boundary", "--$mark-boundary\r\n$mark-shape".toByteArray()),
            phone.pin("$mark-pin"),
            phone.pin("999$mark"),
            phone.form("/pin", "$mark-field=1"),
        )
        assertEquals(22, replies.size)
        for (reply in replies) {
            assertFalse(reply.toString(), reply.text.contains(mark, ignoreCase = true))
            assertFalse(reply.toString(), reply.text.contains(wrong))
            assertEquals(Pages.POLICY, reply.header("Content-Security-Policy"))
            assertEquals("nosniff", reply.header("X-Content-Type-Options"))
            assertEquals("no-referrer", reply.header("Referrer-Policy"))
            assertEquals("no-store", reply.header("Cache-Control"))
            assertEquals("close", reply.header("Connection"))
            assertEquals("text/html; charset=utf-8", reply.header("Content-Type"))
            assertEquals(reply.body.toByteArray().size.toString(), reply.header("Content-Length"))
        }
        assertEquals(setOf(200, 400, 403, 404, 413, 431), replies.map { it.status }.toSet())
    }

    @Test
    fun anAnswerDependsOnWhatHappenedAndNotOnWhatWasSent() {
        val phone = handoffs.open()
        assertArrayEquals(phone.links("https://example.org/wren")!!.raw, phone.links("https://example.org/dunnock", "https://example.org/robin")!!.raw)
        assertArrayEquals(phone.file("wren.json", "{}".toByteArray())!!.raw, phone.file("dunnock.json", "[1, 2, 3]".toByteArray())!!.raw)
        val wrong = if (phone.server.pin.startsWith("1")) listOf("234567", "345678") else listOf("123456", "134567")
        assertArrayEquals(phone.pin(wrong[0])!!.raw, phone.pin(wrong[1])!!.raw)
        assertArrayEquals(phone.get("/${phone.secret}", "User-Agent: one")!!.raw, phone.get("/${phone.secret}", "User-Agent: another", "Accept-Language: de")!!.raw)
    }
}
