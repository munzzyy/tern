package io.github.munzzyy.stamp.core.handoff

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class Reply(val raw: ByteArray) {
    val text: String = String(raw, Charsets.UTF_8)
    val head: List<String> = text.substringBefore("\r\n\r\n").split("\r\n")
    val status: Int = head[0].split(' ')[1].toInt()
    val body: String = text.substringAfter("\r\n\r\n", "")

    fun header(name: String): String? = head.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    override fun toString(): String = "${head[0]} / ${body.substringAfter("<main>").take(300)}"
}

/** Talks to a handoff over real sockets, byte for byte as the test writes them. */
class Phone(val server: HandoffServer) {
    val port: Int = URI(server.address).port
    val host: String = "127.0.0.1:$port"
    val secret: String = server.secretAddress.substringAfterLast('/')

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

    fun links(vararg links: String): Reply? = form("/$secret/links", "links=" + links.joinToString("%0D%0A") { java.net.URLEncoder.encode(it, "UTF-8") })

    fun file(name: String, content: ByteArray): Reply? = post("/$secret/file", "multipart/form-data; boundary=$BOUNDARY", multipart(name, content))

    fun pin(pin: String): Reply? = form("/pin", "pin=$pin")

    companion object {
        const val BOUNDARY = "----StampTestBoundary7MA4YWxk"
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        fun multipart(name: String, content: ByteArray): ByteArray =
            "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/json\r\n\r\n".toByteArray() +
                content + "\r\n--$BOUNDARY--\r\n".toByteArray()
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
