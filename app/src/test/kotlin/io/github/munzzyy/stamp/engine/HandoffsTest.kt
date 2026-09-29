package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.core.handoff.HandoffLimits
import io.github.munzzyy.stamp.core.qr.QrEncoder
import io.github.munzzyy.stamp.engine.real.HandoffTexts
import io.github.munzzyy.stamp.engine.real.Handoffs
import io.github.munzzyy.stamp.engine.real.LocalNetwork
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's side of the handoff, on the loopback address in place of the local network. */
class HandoffsTest {
    private object Sentences : HandoffTexts {
        override fun noLocalNetwork() = "noLocalNetwork"
        override fun behindVpn() = "behindVpn"
        override fun cannotOpen(detail: String?) = "cannotOpen($detail)"
        override fun notOnScreen() = "notOnScreen"
    }

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private var network: LocalNetwork = LocalNetwork.At(loopback)
    private val quick = HandoffLimits(headMs = 1_500, bodyMs = 1_500, lingerMs = 300)
    private val handoffs = Handoffs({ network }, Sentences, { 5_000_000L }, quick)

    @After
    fun close() = handoffs.shutDown()

    private fun open(): Handoff {
        assertNull(runBlocking { handoffs.open() })
        return handoffs.handoff.value!!
    }

    private class Reply(val status: Int, val location: String?)

    private fun send(handoff: Handoff, request: String): Reply {
        val port = URI(handoff.address).port
        val text = Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.replace("HOST", "127.0.0.1:$port").toByteArray())
            socket.getOutputStream().flush()
            String(socket.getInputStream().readBytes())
        }
        val head = text.substringBefore("\r\n\r\n").split("\r\n")
        return Reply(head[0].split(' ')[1].toInt(), head.firstOrNull { it.startsWith("Location: ") }?.removePrefix("Location: "))
    }

    private fun post(handoff: Handoff, path: String, type: String, body: String): Reply =
        send(handoff, "POST $path HTTP/1.1\r\nHost: HOST\r\nContent-Type: $type\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")

    /** The way a phone that cannot scan gets to the page: the PIN is answered with the secret address. */
    private fun page(handoff: Handoff): String = post(handoff, "/pin", "application/x-www-form-urlencoded", "pin=${handoff.code}").location!!

    @Test
    fun anOpenHandoffShowsItsAddressItsPinAndACodeOfTheSecretAddress() {
        val handoff = open()
        assertTrue(handoff.address, Regex("http://127\\.0\\.0\\.1:[0-9]{4,5}").matches(handoff.address))
        assertTrue(handoff.code, Regex("[0-9]{6}").matches(handoff.code))
        assertEquals(5_000_000L + 10 * 60_000, handoff.closesAtMs)
        assertEquals(0, handoff.waiting)

        val path = page(handoff)
        assertTrue(path, Regex("/[a-z2-7]{26}").matches(path))
        assertEquals(200, send(handoff, "GET $path HTTP/1.1\r\nHost: HOST\r\n\r\n").status)
        val expected = QrEncoder.encode(handoff.address + path)
        assertEquals(expected.size, handoff.qr.size)
        val drawn = BooleanArray(expected.size * expected.size) { handoff.qr.isDark(it % expected.size, it / expected.size) }
        assertArrayEquals(expected.squares(), drawn)
    }

    @Test
    fun whatAPhoneSendsIsCountedAndHandedOutOnce() {
        val handoff = open()
        val path = page(handoff)
        assertEquals(200, post(handoff, "$path/links", "application/x-www-form-urlencoded", "links=https%3A%2F%2Fgithub.com%2Fexample%2Fwren%0D%0Acodeberg.org%2Fexample%2Fdunnock").status)
        assertEquals(2, handoffs.handoff.value!!.waiting)
        val boundary = "----StampTestBoundary"
        val export = exportOf("Wren")
        val file = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"stamp-apps.json\"\r\nContent-Type: application/json\r\n\r\n$export\r\n--$boundary--\r\n"
        assertEquals(200, post(handoff, "$path/file", "multipart/form-data; boundary=$boundary", file).status)
        assertEquals(3, handoffs.handoff.value!!.waiting)
        assertEquals(handoff.copy(waiting = 3), handoffs.handoff.value)

        val taken = handoffs.take()
        assertEquals(listOf<Received>(Received.Link("https://github.com/example/wren"), Received.Link("codeberg.org/example/dunnock")), taken.take(2))
        val received = taken[2] as Received.ExportFile
        assertEquals("stamp-apps.json", received.name)
        assertEquals(export, String(received.bytes))
        assertEquals(0, handoffs.handoff.value!!.waiting)
        assertEquals(emptyList<Received>(), handoffs.take())
    }

    @Test
    fun withoutALocalNetworkThereIsNoHandoff() {
        network = LocalNetwork.None
        assertEquals(Problem(ProblemKind.NETWORK, "noLocalNetwork"), runBlocking { handoffs.open() })
        network = LocalNetwork.Vpn
        assertEquals(Problem(ProblemKind.NETWORK, "behindVpn"), runBlocking { handoffs.open() })
        assertNull(handoffs.handoff.value)
        network = LocalNetwork.At(InetAddress.getByName("192.0.2.1"))
        val problem = runBlocking { handoffs.open() }!!
        assertEquals(ProblemKind.NETWORK, problem.kind)
        assertTrue(problem.message, problem.message.startsWith("cannotOpen("))
        assertNull(handoffs.handoff.value)
        assertEquals(emptyList<Received>(), handoffs.take())
    }

    @Test
    fun aNetworkThatIsGoneLeavesTheOpenHandoffAsItIs() {
        val handoff = open()
        network = LocalNetwork.None
        assertEquals(ProblemKind.NETWORK, runBlocking { handoffs.open() }!!.kind)
        assertEquals(handoff, handoffs.handoff.value)
    }

    @Test
    fun closingFreesThePortAndDropsWhatWasNotTaken() {
        val handoff = open()
        assertEquals(200, post(handoff, "${page(handoff)}/links", "application/x-www-form-urlencoded", "links=example.org").status)
        handoffs.close()
        assertNull(handoffs.handoff.value)
        assertThrows(ConnectException::class.java) { send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
        assertEquals(emptyList<Received>(), handoffs.take())
        handoffs.close()
    }

    @Test
    fun openingAgainClosesTheFirstAndMakesEverythingNew() {
        val first = open()
        val firstPath = page(first)
        assertEquals(200, post(first, "$firstPath/links", "application/x-www-form-urlencoded", "links=example.org").status)
        val second = open()
        assertThrows(ConnectException::class.java) { send(first, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
        assertEquals(0, second.waiting)
        assertEquals(emptyList<Received>(), handoffs.take())
        assertNotEquals(firstPath, page(second))
        assertEquals(404, send(second, "GET $firstPath HTTP/1.1\r\nHost: HOST\r\n\r\n").status)
    }

    @Test
    fun leavingTheScreenEndsTheHandoffAndKeepsWhatHasArrived() {
        val handoff = open()
        assertEquals(200, post(handoff, "${page(handoff)}/links", "application/x-www-form-urlencoded", "links=example.org").status)
        handoffs.leftScreen()
        assertNull(handoffs.handoff.value)
        assertThrows(ConnectException::class.java) { send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
        assertEquals(Problem(ProblemKind.UNSUPPORTED, "notOnScreen"), runBlocking { handoffs.open() })
        assertNull(handoffs.handoff.value)

        handoffs.cameOnScreen()
        assertNull(handoffs.handoff.value)
        assertEquals(listOf<Received>(Received.Link("example.org")), handoffs.take())
        assertEquals(0, open().waiting)
    }

    @Test
    fun aHandoffThatEndsByItselfIsGoneFromTheScreenAndKeepsWhatHasArrived() {
        val handoff = open()
        assertEquals(200, post(handoff, "${page(handoff)}/links", "application/x-www-form-urlencoded", "links=example.org").status)
        val wrong = if (handoff.code == "000000") "000001" else "000000"
        repeat(5) { assertEquals(403, post(handoff, "/pin", "application/x-www-form-urlencoded", "pin=$wrong").status) }
        assertNull(handoffs.handoff.value)
        assertEquals(listOf<Received>(Received.Link("example.org")), handoffs.take())
        assertNull(handoffs.handoff.value)
    }
}
