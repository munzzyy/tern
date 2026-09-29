package io.github.munzzyy.stamp.enginetest

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A plain HTTP/1.1 server on 127.0.0.1 that hands each request to [handler] and closes the connection after it. */
class LoopbackServer(private val handler: (Request, OutputStream) -> Unit) : Closeable {
    class Request(val method: String, val target: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<Request>()

    init {
        thread(isDaemon = true, name = "loopback-server") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        val input = BufferedInputStream(it.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
        }
        val request = Request(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers)
        requests += request
        try {
            handler(request, it.getOutputStream())
            it.getOutputStream().flush()
        } catch (_: IOException) {
            return
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
        }
    }

    override fun close() = socket.close()

    companion object {
        fun head(out: OutputStream, status: String, vararg headers: Pair<String, String>) {
            val text = buildString {
                append("HTTP/1.1 $status\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Connection: close\r\n\r\n")
            }
            out.write(text.toByteArray(Charsets.US_ASCII))
        }
    }
}
