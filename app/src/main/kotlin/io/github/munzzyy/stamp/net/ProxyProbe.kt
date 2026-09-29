package io.github.munzzyy.stamp.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Asks whether a SOCKS proxy answers on this device. It is the only place outside the handoff
 * that makes a socket, and that socket goes to 127.0.0.1 and nowhere else: it says hello the way
 * SOCKS 5 does and hangs up. Nothing is sent through the proxy.
 */
object ProxyProbe {
    private val THIS_DEVICE = byteArrayOf(127, 0, 0, 1)
    private const val SOCKS_5 = 5
    private const val NO_LOGIN = 0

    fun answers(port: Int, withinMs: Int = 1_500): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.soTimeout = withinMs
                socket.connect(InetSocketAddress(InetAddress.getByAddress(THIS_DEVICE), port), withinMs)
                socket.getOutputStream().apply {
                    write(byteArrayOf(SOCKS_5.toByte(), 1, NO_LOGIN.toByte()))
                    flush()
                }
                val answer = socket.getInputStream()
                answer.read() == SOCKS_5 && answer.read() == NO_LOGIN
            }
        } catch (_: IOException) {
            false
        }
    }
}
