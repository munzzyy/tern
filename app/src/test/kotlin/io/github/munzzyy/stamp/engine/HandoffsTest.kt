package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.core.handoff.HandoffLimits
import io.github.munzzyy.stamp.core.handoff.Seal
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
import org.junit.Assert.assertFalse
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
    private var sent = 0

    @After
    fun close() = handoffs.shutDown()

    private fun open(): Handoff {
        assertNull(runBlocking { handoffs.open() })
        return handoffs.handoff.value!!
    }

    private class Reply(val status: Int, val text: String)

    private fun send(handoff: Handoff, request: ByteArray): Reply {
        val port = URI(handoff.address).port
        val text = Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            String(socket.getInputStream().readBytes())
        }
        return Reply(text.substringBefore("\r\n").split(' ')[1].toInt(), text)
    }

    private fun get(handoff: Handoff, path: String): Reply =
        send(handoff, "GET $path HTTP/1.1\r\nHost: 127.0.0.1:${URI(handoff.address).port}\r\n\r\n".toByteArray())

    /** What the page does in the browser of the phone: seal with the code the screen shows, and post. */
    private fun post(handoff: Handoff, kind: Int, plain: ByteArray, code: String = handoff.code): Reply {
        val nonce = ByteArray(Seal.NONCE).also { it[0] = (++sent).toByte() }
        val body = "sealed=" + Seal.text(Seal(code.replace(" ", "")).seal(kind, nonce, plain))
        val head = "POST /send HTTP/1.1\r\nHost: 127.0.0.1:${URI(handoff.address).port}\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n"
        return send(handoff, (head + body).toByteArray())
    }

    private fun links(handoff: Handoff, vararg links: String): Reply = post(handoff, Seal.LINKS, links.joinToString("\n").toByteArray())

    @Test
    fun anOpenHandoffShowsItsAddressItsCodeAndAQrCodeOfBoth() {
        val handoff = open()
        assertTrue(handoff.address, Regex("http://127\\.0\\.0\\.1:[0-9]{4,5}").matches(handoff.address))
        assertTrue(handoff.code, Regex("[a-z2-7]{4}( [a-z2-7]{4}){4}").matches(handoff.code))
        assertEquals(5_000_000L + 10 * 60_000, handoff.closesAtMs)
        assertEquals(0, handoff.waiting)

        val expected = QrEncoder.encode(handoff.address + "/#" + handoff.code.replace(" ", ""))
        assertEquals(expected.size, handoff.qr.size)
        val drawn = BooleanArray(expected.size * expected.size) { handoff.qr.isDark(it % expected.size, it / expected.size) }
        assertArrayEquals(expected.squares(), drawn)
        assertTrue("version ${expected.version}", expected.version <= 4)
    }

    @Test
    fun thereIsNoEndBeforeTheFirstHandoffAndWhileOneIsOpen() {
        assertNull(handoffs.handoffEnd.value)
        handoffs.close()
        handoffs.leftScreen()
        handoffs.cameOnScreen()
        assertNull(handoffs.handoffEnd.value)
        val handoff = open()
        assertNull(handoffs.handoffEnd.value)
        assertEquals(200, links(handoff, "example.org").status)
        handoffs.take()
        assertNull(handoffs.handoffEnd.value)
    }

    @Test
    fun aHandoffThatWasClosedHereSaysSoUntilTheNextOneOpens() {
        open()
        handoffs.close()
        assertEquals(HandoffEnd.CLOSED, handoffs.handoffEnd.value)
        handoffs.close()
        handoffs.take()
        assertEquals(HandoffEnd.CLOSED, handoffs.handoffEnd.value)
        open()
        assertNull(handoffs.handoffEnd.value)
        open()
        assertNull(handoffs.handoffEnd.value)
    }

    @Test
    fun aHandoffThatCouldNotBeOpenedAgainWasClosedHere() {
        val handoff = open()
        network = LocalNetwork.At(InetAddress.getByName("192.0.2.1"))
        assertEquals(ProblemKind.NETWORK, runBlocking { handoffs.open() }!!.kind)
        assertNull(handoffs.handoff.value)
        assertEquals(HandoffEnd.CLOSED, handoffs.handoffEnd.value)
        assertThrows(ConnectException::class.java) { get(handoff, "/") }
    }

    @Test
    fun aHandoffWhoseTimeIsOverSaysSoWhateverIsDoneAfterwards() {
        val short = Handoffs({ network }, Sentences, { 5_000_000L }, quick.copy(lifeMs = 300))
        try {
            assertNull(runBlocking { short.open() })
            assertNull(short.handoffEnd.value)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (short.handoff.value != null && System.nanoTime() < deadline) Thread.sleep(10)
            assertNull(short.handoff.value)
            assertEquals(HandoffEnd.EXPIRED, short.handoffEnd.value)
            short.leftScreen()
            short.cameOnScreen()
            assertEquals(HandoffEnd.EXPIRED, short.handoffEnd.value)
            short.close()
            assertEquals(HandoffEnd.EXPIRED, short.handoffEnd.value)
        } finally {
            short.shutDown()
        }
    }

    @Test
    fun thePageNeverHoldsTheCode() {
        val handoff = open()
        val page = get(handoff, "/")
        assertEquals(200, page.status)
        assertFalse(page.text.contains(handoff.code))
        assertFalse(page.text.contains(handoff.code.replace(" ", "")))
        assertFalse(links(handoff, "example.org").text.contains(handoff.code.replace(" ", "")))
    }

    @Test
    fun whatAPhoneSendsIsCountedAndHandedOutOnce() {
        val handoff = open()
        assertEquals(200, links(handoff, "https://github.com/example/wren", "codeberg.org/example/dunnock").status)
        assertEquals(2, handoffs.handoff.value!!.waiting)
        val export = exportOf("Wren")
        val name = "stamp-apps.json".toByteArray()
        assertEquals(200, post(handoff, Seal.FILE, byteArrayOf(name.size.toByte()) + name + export.toByteArray()).status)
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
    fun whatWasNotSealedWithTheCodeOnTheScreenDoesNotArrive() {
        val handoff = open()
        val other = if (handoff.code.endsWith('a')) handoff.code.dropLast(1) + 'b' else handoff.code.dropLast(1) + 'a'
        repeat(10) { assertEquals(403, post(handoff, Seal.LINKS, "example.org".toByteArray(), other).status) }
        assertEquals(handoff, handoffs.handoff.value)
        assertEquals(emptyList<Received>(), handoffs.take())
        assertEquals(200, links(handoff, "example.org").status)
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
        assertEquals(200, links(handoff, "example.org").status)
        handoffs.close()
        assertNull(handoffs.handoff.value)
        assertThrows(ConnectException::class.java) { get(handoff, "/") }
        assertEquals(emptyList<Received>(), handoffs.take())
        handoffs.close()
    }

    @Test
    fun openingAgainClosesTheFirstAndMakesEverythingNew() {
        val first = open()
        assertEquals(200, links(first, "example.org").status)
        val second = open()
        assertThrows(ConnectException::class.java) { get(first, "/") }
        assertEquals(0, second.waiting)
        assertEquals(emptyList<Received>(), handoffs.take())
        assertNotEquals(first.code, second.code)
        assertNotEquals(first.address, second.address)
        assertEquals(403, post(second, Seal.LINKS, "example.org".toByteArray(), first.code).status)
    }

    @Test
    fun leavingTheScreenEndsTheHandoffAndKeepsWhatHasArrived() {
        val handoff = open()
        assertEquals(200, links(handoff, "example.org").status)
        handoffs.leftScreen()
        assertNull(handoffs.handoff.value)
        assertEquals(HandoffEnd.LEFT_SCREEN, handoffs.handoffEnd.value)
        assertThrows(ConnectException::class.java) { get(handoff, "/") }
        assertEquals(Problem(ProblemKind.UNSUPPORTED, "notOnScreen"), runBlocking { handoffs.open() })
        assertNull(handoffs.handoff.value)

        handoffs.cameOnScreen()
        assertNull(handoffs.handoff.value)
        assertEquals(listOf<Received>(Received.Link("example.org")), handoffs.take())
        assertEquals(HandoffEnd.LEFT_SCREEN, handoffs.handoffEnd.value)
        assertEquals(0, open().waiting)
        assertNull(handoffs.handoffEnd.value)
        handoffs.close()
        assertEquals(HandoffEnd.CLOSED, handoffs.handoffEnd.value)
    }

    @Test
    fun aHandoffThatStampLeftWhileItWasOpeningIsClosedAgain() {
        var leave: (() -> Unit)? = null
        val late = Handoffs({ network }, Sentences, { leave?.invoke(); 5_000_000L }, quick)
        leave = {
            leave = null
            late.leftScreen()
        }
        try {
            assertEquals(Problem(ProblemKind.UNSUPPORTED, "notOnScreen"), runBlocking { late.open() })
            assertNull("the screen was left before the handoff could be shown", late.handoff.value)
            late.cameOnScreen()
            assertNull(runBlocking { late.open() })
            assertTrue(late.handoff.value != null)
        } finally {
            late.shutDown()
        }
    }

    @Test
    fun aHandoffThatEndsByItselfIsGoneFromTheScreenAndKeepsWhatHasArrived() {
        val few = Handoffs({ network }, Sentences, { 5_000_000L }, quick.copy(requests = 3))
        try {
            assertNull(runBlocking { few.open() })
            val handoff = few.handoff.value!!
            assertEquals(200, links(handoff, "example.org").status)
            assertEquals(200, get(handoff, "/").status)
            assertEquals(404, get(handoff, "/nothing").status)
            assertEquals(handoff.copy(waiting = 1), few.handoff.value)
            assertEquals(429, get(handoff, "/").status)
            assertNull(few.handoff.value)
            assertEquals(HandoffEnd.USED_UP, few.handoffEnd.value)
            assertEquals(listOf<Received>(Received.Link("example.org")), few.take())
            assertNull(few.handoff.value)
            assertEquals(HandoffEnd.USED_UP, few.handoffEnd.value)
        } finally {
            few.shutDown()
        }
    }
}
