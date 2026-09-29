package io.github.munzzyy.tern.core.handoff

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

    private fun Phone.link(): String = Phone.field(sealed(Seal.LINKS, "https://example.org/app".toByteArray()))

    private fun Phone.sendHead(length: Int, vararg headers: String): String =
        "POST /send HTTP/1.1\r\nHost: $host\r\nContent-Type: ${Forms.TYPE}\r\n" + headers.joinToString("") { "$it\r\n" } + "Content-Length: $length\r\n\r\n"

    private fun said(reply: Reply?, notice: Notice): Boolean = reply!!.body.contains(">${notice.text(limits)}</p>")

    private fun flipped(bytes: ByteArray, at: Int): ByteArray = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    @Test
    fun whatIsNotOneOfTheTwoRequestsIsAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        assertEquals(404, nothing.status)
        val gets = listOf(
            "/send", "/send/", "/index.html", "/?a=1", "/#a", "/#${phone.code}", "/${phone.code}", "//", "/pin", "/links", "/file", "/favicon.ico",
            "/a".repeat(2000), "*", "send", "http://${phone.host}/",
        )
        for (path in gets) assertArrayEquals(path, nothing.raw, phone.get(path)!!.raw)

        val posts = listOf("/", "/send/", "/send?a=1", "/send#a", "/SEND", "/Send", "//send", "/send/links", "/pin", "/links", "/file", "send", "http://${phone.host}/send")
        for (path in posts) assertArrayEquals(path, nothing.raw, phone.form(path, phone.link())!!.raw)
        for (method in listOf("HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "get", "TRACE")) {
            assertArrayEquals(method, nothing.raw, phone.raw(phone.head(method, "/"))!!.raw)
            assertArrayEquals(method, nothing.raw, phone.raw(phone.head(method, "/send"))!!.raw)
        }
        assertArrayEquals(nothing.raw, phone.raw(phone.head("post", "/send", "Content-Type: ${Forms.TYPE}", "Content-Length: 0"))!!.raw)
        assertEquals(0, phone.server.waiting())
        assertTrue(phone.server.isOpen)
    }

    @Test
    fun aPathThatClimbsOrHidesIsAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        val paths = listOf(
            "/../send", "/send/..", "/send/../send", "/./send", "/x/../send", "/..", "/.", "/../../../../etc/passwd",
            "/%2e%2e/send", "/send/%2e%2e/send", "/%2E%2E%2Fsend", "/%73end", "/send%2F", "/%2F",
            "/send\u0000", "/\u0000send", "/\u0000", "/send%00", "/%00", "/send\u0000.html", "/send\t", "/send\u00e9", "/\u00e9",
        )
        for (path in paths) {
            assertArrayEquals(path, nothing.raw, phone.get(path)!!.raw)
            assertArrayEquals(path, nothing.raw, phone.form(path, phone.link())!!.raw)
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aRequestForAnotherHostIsAnsweredLikeAnAddressThatDoesNotExist() {
        val phone = handoffs.open()
        val nothing = phone.get("/nothing")!!
        for (host in listOf("rebound.example:${phone.port}", "127.0.0.1", "localhost:${phone.port}", "127.0.0.1:${phone.port + 1}", "127.0.0.1:${phone.port} ,x", "")) {
            assertArrayEquals(host, nothing.raw, phone.raw("GET / HTTP/1.1\r\nHost: $host\r\n\r\n")!!.raw)
            val body = phone.link()
            assertArrayEquals(host, nothing.raw, phone.raw("POST /send HTTP/1.1\r\nHost: $host\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: ${body.length}\r\n\r\n$body")!!.raw)
        }
        assertArrayEquals(nothing.raw, phone.raw("GET / HTTP/1.1\r\n\r\n")!!.raw)
        assertArrayEquals(nothing.raw, phone.raw("GET / HTTP/1.0\r\n\r\n")!!.raw)
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(200, phone.raw("GET / HTTP/1.1\r\nhOST:${phone.host}  \r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun headersOfEightKibibytesAreReadAndOneByteMoreIsNot() {
        val phone = handoffs.open()
        assertEquals(8 * 1024, limits.headBytes)
        fun headOf(size: Int): String {
            val start = "GET / HTTP/1.1\r\nHost: ${phone.host}\r\nX-Fill: "
            return start + "f".repeat(size - start.length - 4) + "\r\n\r\n"
        }
        assertEquals(8192, headOf(8192).length)
        assertEquals(200, phone.raw(headOf(8192))!!.status)
        for (size in listOf(8193, 8194, 8200, 16 * 1024, 300 * 1024)) assertEquals("$size", 431, phone.raw(headOf(size))!!.status)
        val manySmall = "GET / HTTP/1.1\r\nHost: ${phone.host}\r\n" + "X-A: b\r\n".repeat(2000) + "\r\n"
        assertEquals(431, phone.raw(manySmall)!!.status)
        assertEquals(431, phone.raw("GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
    }

    @Test
    fun aBodyThatFollowsHeadersOfEightKibibytesIsStillRead() {
        val phone = handoffs.open()
        val body = phone.link()
        val start = "POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: ${body.length}\r\nX-Fill: "
        val head = start + "f".repeat(8192 - start.length - 4) + "\r\n\r\n"
        assertTrue(said(phone.raw(head + body), Notice.LINKS_SENT))
        assertEquals(listOf(HandoffItem.Link("https://example.org/app")), phone.server.take())
    }

    @Test
    fun aBodyWithoutALengthIsRefused() {
        val phone = handoffs.open()
        val reply = phone.raw("POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\n\r\n${phone.link()}")!!
        assertEquals(411, reply.status)
        assertEquals(411, phone.raw("POST /nothing HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aChunkedBodyIsRefused() {
        val phone = handoffs.open()
        val chunk = phone.link()
        val chunked = "${chunk.length.toString(16)}\r\n$chunk\r\n0\r\n\r\n"
        val start = "POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\n"
        assertEquals(411, phone.raw(start + "Transfer-Encoding: chunked\r\n\r\n" + chunked)!!.status)
        assertEquals(411, phone.raw(start + "Transfer-Encoding: chunked\r\nContent-Length: ${chunk.length}\r\n\r\n" + chunk)!!.status)
        assertEquals(411, phone.raw(start + "Content-Length: ${chunked.length}\r\nTransfer-Encoding: identity\r\n\r\n" + chunked)!!.status)
        assertEquals(400, phone.raw(start + "Transfer-Encoding: chunked\r\nTransfer-Encoding: identity\r\n\r\n" + chunked)!!.status)
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")!!.status)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aBodyLongerThanItSaysIsRefused() {
        val phone = handoffs.open()
        val body = phone.link()
        val start = phone.sendHead(body.length)
        assertEquals(400, phone.raw(start + body + "AAAA")!!.status)
        assertEquals(400, phone.raw(start + body + "\r\n")!!.status)
        assertEquals(400, phone.raw(start + body + "GET / HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n")!!.status)
        assertEquals(400, phone.raw(phone.sendHead(body.length - 1) + body)!!.status)
        assertEquals(0, phone.server.waiting())
        assertEquals(200, phone.raw(start + body)!!.status)
        assertEquals(1, phone.server.waiting())
    }

    @Test
    fun aBodyShorterThanItSaysIsNotTaken() {
        val phone = handoffs.open()
        val body = phone.link()
        val start = phone.sendHead(body.length + 10)
        phone.connect().use { socket ->
            socket.getOutputStream().write((start + body).toByteArray())
            socket.shutdownOutput()
            assertNull(phone.read(socket))
        }
        val began = System.nanoTime()
        assertEquals(408, phone.raw(start + body)!!.status)
        val waited = (System.nanoTime() - began) / 1_000_000
        assertTrue("$waited ms", waited in limits.bodyMs - 100..limits.bodyMs + 1_500)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aLengthThatIsNoNumberIsRefused() {
        val phone = handoffs.open()
        val body = "sealed=AAAA"
        for (length in listOf("", "-1", "+11", "11.0", "0xb", "1 1", "eleven", "1,1", "011a")) {
            val reply = phone.raw("POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: $length\r\n\r\n$body")!!
            assertEquals(length, 400, reply.status)
        }
        val twice = phone.raw("POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: 11\r\nContent-Length: 11\r\n\r\n$body")!!
        assertEquals(400, twice.status)
        for (huge in listOf("99999999999999999999", "4294967307", "2147483648", "999999999", "${limits.sendBytes + 1}")) {
            val reply = phone.raw("POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: $huge\r\n\r\n$body")!!
            assertEquals(huge, 413, reply.status)
            assertTrue(huge, said(reply, Notice.TOO_LARGE))
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aBodyWhereNoneBelongsIsRefused() {
        val phone = handoffs.open()
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 5\r\n\r\nhello")!!.status)
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\n\r\nhello")!!.status)
        assertEquals(400, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 1\r\n\r\n")!!.status)
        assertEquals(200, phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: 0\r\n\r\n")!!.status)
    }

    @Test
    fun everyWayOfTamperingGetsTheSameAnswer() {
        val phone = handoffs.open()
        val other = Seal(phone.code.dropLast(1) + if (phone.code.last() == 'a') 'b' else 'a')
        val same = phone.send(other.seal(Seal.LINKS, ByteArray(12), "https://example.org/app".toByteArray()))!!
        assertEquals(403, same.status)
        assertTrue(same.toString(), said(same, Notice.DID_NOT_OPEN))

        val sealed = phone.sealed(Seal.LINKS, "https://example.org/app\nhttps://example.org/another".toByteArray())
        val tag = sealed.size - 32
        val tampered = mapOf(
            "a bit in the kind" to flipped(sealed, 0),
            "the kind of a file" to sealed.copyOf().also { it[0] = Seal.FILE.toByte() },
            "a bit at the start of the nonce" to flipped(sealed, 1),
            "a bit at the end of the nonce" to flipped(sealed, 12),
            "a bit at the start of the content" to flipped(sealed, 13),
            "a bit at the end of the content" to flipped(sealed, tag - 1),
            "a bit at the start of the tag" to flipped(sealed, tag),
            "a bit at the end of the tag" to flipped(sealed, sealed.size - 1),
            "the last byte cut off" to sealed.copyOf(sealed.size - 1),
            "the tag cut off" to sealed.copyOf(tag),
            "half of it cut off" to sealed.copyOf(sealed.size / 2),
            "nothing but kind and nonce" to sealed.copyOf(13),
            "one byte fewer than the shortest" to sealed.copyOf(44),
            "nothing" to ByteArray(0),
            "a byte more" to sealed + byteArrayOf(0),
            "a tag of zeros" to sealed.copyOf(tag) + ByteArray(32),
            "the content of another with this tag" to sealed.copyOf(13) + ByteArray(tag - 13) + sealed.copyOfRange(tag, sealed.size),
            "sealed with another code" to other.seal(Seal.LINKS, sealed.copyOfRange(1, 13), "https://example.org/app".toByteArray()),
        )
        for ((what, bytes) in tampered) assertArrayEquals(what, same.raw, phone.send(bytes)!!.raw)

        val text = Seal.text(sealed)
        val misspelled = listOf(
            "sealed=" + text.dropLast(1), "sealed=" + text.dropLast(2), "sealed=$text=", "sealed=$text==", "sealed=$text&sealed=$text", "sealed=$text&a=b",
            "sealed=" + text.replace('-', '+').replace('_', '/'), "sealed=%41" + text.drop(1), "sealed= $text", "sealed=$text\r\n", "Sealed=$text", "links=$text",
            text, "sealed", "sealed=", "",
        )
        for (body in misspelled) assertArrayEquals(body.take(40), same.raw, phone.form("/send", body)!!.raw)

        assertEquals(0, phone.server.waiting())
        assertEquals(0, handoffs.changes.get())
        assertTrue(phone.server.isOpen)
        assertTrue(said(phone.send(sealed), Notice.LINKS_SENT))
        assertEquals(2, phone.server.waiting())
    }

    @Test
    fun whatWasTakenOnceIsNotTakenASecondTime() {
        val phone = handoffs.open()
        val other = Seal(phone.code.dropLast(1) + if (phone.code.last() == 'a') 'b' else 'a')
        val notOpened = phone.send(other.seal(Seal.LINKS, ByteArray(12), "https://example.org/app".toByteArray()))!!
        val links = phone.sealed(Seal.LINKS, "https://example.org/app".toByteArray())
        val file = phone.sealed(Seal.FILE, Phone.named("apps.json", "{}".toByteArray()))
        assertTrue(said(phone.send(links), Notice.LINKS_SENT))
        assertTrue(said(phone.send(file), Notice.FILE_SENT))
        repeat(3) {
            assertArrayEquals(notOpened.raw, phone.send(links)!!.raw)
            assertArrayEquals(notOpened.raw, phone.send(file)!!.raw)
        }
        assertEquals(2, phone.server.waiting())
        phone.server.take()
        assertArrayEquals(notOpened.raw, phone.send(links)!!.raw)
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aThingThatWasRefusedForWhatIsInItCannotBeSentAgainEither() {
        val phone = handoffs.open()
        val many = phone.sealed(Seal.LINKS, Array(21) { "https://example.org/app-$it" }.joinToString("\n").toByteArray())
        assertTrue(said(phone.send(many), Notice.TOO_MANY_LINKS))
        assertTrue(said(phone.send(many), Notice.DID_NOT_OPEN))
    }

    @Test
    fun whatDoesNotComeAsTheFormOfThePageIsNotRead() {
        val phone = handoffs.open()
        val body = phone.link().toByteArray()
        for (type in listOf("text/plain", "application/json", "multipart/form-data; boundary=abc", "application/x-www-form-urlencoded2", "")) {
            val reply = phone.post("/send", type, body)!!
            assertEquals(type, 400, reply.status)
            assertTrue(type, said(reply, Notice.UNREADABLE))
        }
        assertTrue(said(phone.raw("POST /send HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Length: ${body.size}\r\n\r\n" + String(body)), Notice.UNREADABLE))
        assertEquals(0, phone.server.waiting())
        assertTrue(said(phone.post("/send", "Application/X-WWW-Form-Urlencoded; charset=UTF-8", body), Notice.LINKS_SENT))
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
        val other = Seal(phone.code.dropLast(1) + if (phone.code.last() == 'a') 'b' else 'a')
        val replies = listOfNotNull(
            phone.get("/$mark-path"),
            phone.get("/?$mark-query=$mark-value"),
            phone.get("/", "X-$mark-name: $mark-value", "User-Agent: $mark-agent", "Referer: http://$mark-referer/", "Cookie: $mark-cookie=1", "Origin: http://$mark-origin"),
            phone.raw("$mark-method / HTTP/1.1\r\nHost: ${phone.host}\r\n\r\n"),
            phone.raw("GET / HTTP/1.1\r\nHost: $mark-host\r\n\r\n"),
            phone.raw("GET / $mark-version\r\nHost: ${phone.host}\r\n\r\n"),
            phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\n$mark-no-colon\r\n\r\n"),
            phone.raw("GET / HTTP/1.1\r\nHost: ${phone.host}\r\nX-Long: $mark" + "f".repeat(9000) + "\r\n\r\n"),
            phone.links("https://example.org/$mark-link"),
            phone.links(*Array(21) { "https://example.org/$mark-many-$it" }),
            phone.links("https://example.org/$mark-long" + "a".repeat(2000)),
            phone.links("https://example.org/$mark-hidden\u202e"),
            phone.file("$mark-name.json", "{\"$mark-content\":1}".toByteArray()),
            phone.file("$mark-large.json", ByteArray(limits.fileBytes + 1) { 'q'.code.toByte() }),
            phone.plain(Seal.FILE, byteArrayOf(90) + "$mark-shape".toByteArray()),
            phone.send(other.seal(Seal.LINKS, ByteArray(12), "https://example.org/$mark-other-code".toByteArray())),
            phone.form("/send", "sealed=$mark"),
            phone.form("/send", "$mark-field=$mark-value"),
            phone.form("/$mark-send", phone.link()),
            phone.post("/send", "text/$mark-type", phone.link().toByteArray()),
        )
        assertEquals(20, replies.size)
        for (reply in replies) {
            assertFalse(reply.toString(), reply.text.contains(mark, ignoreCase = true))
            assertFalse(reply.toString(), reply.text.contains(Seal.text(mark.toByteArray())))
            assertEquals(Pages.POLICY, reply.header("Content-Security-Policy"))
            assertEquals("nosniff", reply.header("X-Content-Type-Options"))
            assertEquals("no-referrer", reply.header("Referrer-Policy"))
            assertEquals("no-store", reply.header("Cache-Control"))
            assertEquals("close", reply.header("Connection"))
            assertEquals("text/html; charset=utf-8", reply.header("Content-Type"))
            assertEquals(reply.body.toByteArray().size.toString(), reply.header("Content-Length"))
            assertNull(reply.header("Location"))
            assertNull(reply.header("Set-Cookie"))
            assertNull(reply.header("Access-Control-Allow-Origin"))
        }
        assertEquals(setOf(200, 400, 403, 404, 413, 431), replies.map { it.status }.toSet())
    }

    @Test
    fun anAnswerDependsOnWhatHappenedAndNotOnWhatWasSent() {
        val phone = handoffs.open()
        assertArrayEquals(phone.links("https://example.org/wren")!!.raw, phone.links("https://example.org/dunnock", "https://example.org/robin")!!.raw)
        assertArrayEquals(phone.file("wren.json", "{}".toByteArray())!!.raw, phone.file("dunnock.json", "[1, 2, 3]".toByteArray())!!.raw)
        assertArrayEquals(phone.get("/", "User-Agent: one")!!.raw, phone.get("/", "User-Agent: another", "Accept-Language: de")!!.raw)
    }
}
