package io.github.munzzyy.stamp.core.handoff

import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** Ends the reading of a request with the answer to give instead. */
internal class Refused(val answer: Answer) : Exception()

/** The other side went away before its request was whole, so there is nobody to answer. */
internal class Gone : IOException("The connection ended inside a request")

internal class Head(val method: String, val target: String, private val headers: Map<String, String>) {
    /** Names are in lower case. */
    fun header(name: String): String? = headers[name]
}

/** Reads one request from a socket, never more than it was told to and never for longer. */
internal class Wire(private val socket: Socket) {
    private val input: InputStream = socket.getInputStream()
    private var spare = ByteArray(0)

    fun head(limit: Int, withinMs: Long): Head {
        val deadline = System.nanoTime() + withinMs * NANOS_PER_MS
        val buffer = ByteArray(limit + 1)
        var filled = 0
        var from = 0
        while (true) {
            val end = endOfHead(buffer, from, filled)
            if (end > limit) throw Refused(Pages.headTooLong())
            if (end > 0) {
                spare = buffer.copyOfRange(end, filled)
                return parse(buffer, end - END.size)
            }
            if (filled == buffer.size) throw Refused(Pages.headTooLong())
            val n = read(buffer, filled, buffer.size - filled, deadline)
            if (n < 0) throw Gone()
            from = maxOf(0, filled - END.size + 1)
            filled += n
        }
    }

    /** Exactly [length] bytes. More on the wire than was announced is refused, as far as it can be seen. */
    fun body(length: Int, withinMs: Long): ByteArray {
        if (spare.size > length) throw Refused(Pages.badRequest())
        val deadline = System.nanoTime() + withinMs * NANOS_PER_MS
        val out = try {
            ByteArray(length)
        } catch (_: OutOfMemoryError) {
            throw Refused(Pages.busy())
        }
        System.arraycopy(spare, 0, out, 0, spare.size)
        var filled = spare.size
        spare = ByteArray(0)
        while (filled < length) {
            val n = read(out, filled, length - filled, deadline)
            if (n < 0) throw Gone()
            filled += n
        }
        if (input.available() > 0) throw Refused(Pages.badRequest())
        return out
    }

    fun hasMore(): Boolean = spare.isNotEmpty() || input.available() > 0

    /** Reads and drops what still comes, so that the other side can read its answer before the connection closes. */
    fun drain(limit: Long, withinMs: Long) {
        val deadline = System.nanoTime() + withinMs * NANOS_PER_MS
        val sink = ByteArray(16 * 1024)
        var seen = 0L
        try {
            while (seen < limit) {
                val n = read(sink, 0, sink.size, deadline)
                if (n < 0) return
                seen += n
            }
        } catch (_: Refused) {
            return
        }
    }

    private fun read(into: ByteArray, at: Int, room: Int, deadline: Long): Int {
        val left = (deadline - System.nanoTime()) / NANOS_PER_MS
        if (left <= 0) throw Refused(Pages.tooSlow())
        socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return try {
            input.read(into, at, room)
        } catch (_: SocketTimeoutException) {
            throw Refused(Pages.tooSlow())
        }
    }

    private fun endOfHead(buffer: ByteArray, from: Int, filled: Int): Int {
        var i = from
        while (i + END.size <= filled) {
            if (buffer[i] == END[0] && buffer[i + 1] == END[1] && buffer[i + 2] == END[2] && buffer[i + 3] == END[3]) return i + END.size
            i++
        }
        return -1
    }

    private fun parse(buffer: ByteArray, length: Int): Head {
        val lines = String(buffer, 0, length, Charsets.ISO_8859_1).split("\r\n")
        // The address is taken as it comes: what is odd in it matches no page and is answered like any unknown address.
        val first = lines[0].split(' ')
        if (first.size != 3 || first.any { it.isEmpty() } || first[2] !in VERSIONS) throw Refused(Pages.badRequest())
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line.any { it !in ' '..'~' && it != '\t' }) throw Refused(Pages.badRequest())
            val name = line.substring(0, colon)
            if (name.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in NAME_MARKS) }) throw Refused(Pages.badRequest())
            if (headers.put(name.lowercase(), line.substring(colon + 1).trim()) != null && name.lowercase() in ONCE) throw Refused(Pages.badRequest())
        }
        return Head(first[0], first[1], headers)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val NAME_MARKS = "!#$%&'*+-.^_`|~"
        val END = byteArrayOf(13, 10, 13, 10)
        val VERSIONS = setOf("HTTP/1.1", "HTTP/1.0")
        val ONCE = setOf("host", "content-length", "content-type", "transfer-encoding")
    }
}
