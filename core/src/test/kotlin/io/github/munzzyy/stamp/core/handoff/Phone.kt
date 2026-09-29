package io.github.munzzyy.stamp.core.handoff

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

class Reply(val raw: ByteArray) {
    val text: String = String(raw, Charsets.UTF_8)
    val head: List<String> = text.substringBefore("\r\n\r\n").split("\r\n")
    val status: Int = head[0].split(' ')[1].toInt()
    val body: String = text.substringAfter("\r\n\r\n", "")

    fun header(name: String): String? = head.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    override fun toString(): String = "${head[0]} / ${body.substringAfter("<h1>").take(300)}"
}

/** Talks to a handoff over real sockets, byte for byte as the test writes them. It seals the way the page does. */
class Phone(val server: HandoffServer) {
    val port: Int = URI(server.address).port
    val host: String = "127.0.0.1:$port"
    val code: String = server.code.replace(" ", "")
    private val seal = Seal(code)
    private val random = SecureRandom()

    fun connect(readTimeoutMs: Int = 10_000): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(LOOPBACK, port), 2_000)
        socket.soTimeout = readTimeoutMs
        return socket
    }

    /** Null when the connection ended without an answer. */
    fun exchange(request: ByteArray, readTimeoutMs: Int = 10_000): Reply? = connect(readTimeoutMs).use { socket ->
        try {
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
        } catch (_: IOException) {
            // The handoff may answer and stop listening before everything is written.
        }
        read(socket)
    }

    fun read(socket: Socket): Reply? {
        val out = ByteArrayOutputStream()
        try {
            socket.getInputStream().copyTo(out)
        } catch (e: java.net.SocketTimeoutException) {
            throw e
        } catch (_: IOException) {
            // A reset after the answer still leaves the answer.
        }
        return if (out.size() == 0) null else Reply(out.toByteArray())
    }

    fun head(method: String, path: String, vararg headers: String): String =
        "$method $path HTTP/1.1\r\nHost: $host\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"

    fun get(path: String, vararg headers: String): Reply? = exchange(head("GET", path, *headers).toByteArray(Charsets.ISO_8859_1))

    fun post(path: String, type: String, body: ByteArray, vararg headers: String): Reply? =
        exchange(head("POST", path, "Content-Type: $type", "Content-Length: ${body.size}", *headers).toByteArray(Charsets.ISO_8859_1) + body)

    fun form(path: String, body: String): Reply? = post(path, Forms.TYPE, body.toByteArray(Charsets.ISO_8859_1))

    fun sealed(kind: Int, plain: ByteArray): ByteArray = seal.seal(kind, ByteArray(Seal.NONCE).also(random::nextBytes), plain)

    fun send(sealed: ByteArray): Reply? = form("/send", field(sealed))

    fun plain(kind: Int, plain: ByteArray): Reply? = send(sealed(kind, plain))

    fun links(vararg links: String): Reply? = plain(Seal.LINKS, links.joinToString("\n").toByteArray())

    fun file(name: String, content: ByteArray): Reply? = plain(Seal.FILE, named(name, content))

    companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        fun field(sealed: ByteArray): String = "sealed=" + Seal.text(sealed)

        fun named(name: String, content: ByteArray): ByteArray {
            val bytes = name.toByteArray()
            return byteArrayOf(bytes.size.toByte()) + bytes + content
        }
    }
}

/** Opens handoffs on the loopback address and closes what a test has left open. */
class Handoffs {
    private val opened = ArrayList<HandoffServer>()
    val changes = AtomicInteger()

    fun open(limits: HandoffLimits = QUICK): Phone {
        val server = HandoffServer.open(Phone.LOOPBACK, limits, onChange = { changes.incrementAndGet() })
        opened += server
        return Phone(server)
    }

    fun closeAll() = opened.forEach { it.close() }

    companion object {
        /** The limits of the app, with times short enough for a test that waits for them. */
        val QUICK = HandoffLimits(headMs = 1_500, bodyMs = 1_500, lingerMs = 300)
    }
}

fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
}

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
