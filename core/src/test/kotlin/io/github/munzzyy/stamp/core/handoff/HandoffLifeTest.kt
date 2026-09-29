package io.github.munzzyy.stamp.core.handoff

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** How long a handoff lives, how much it does at once, and how it ends. Real sockets on the loopback address. */
class HandoffLifeTest {
    private val handoffs = Handoffs()
    private val limits = Handoffs.QUICK

    @After
    fun closeAll() = handoffs.closeAll()

    private fun assertClosed(phone: Phone) {
        assertFalse(phone.server.isOpen)
        assertThrows(ConnectException::class.java) { phone.get("/${phone.secret}") }
        assertThrows(ConnectException::class.java) { phone.pin(phone.server.pin) }
    }

    @Test
    fun fiveWrongPinsCloseTheHandoffForGoodAndTheRightOneComesTooLate() {
        val phone = handoffs.open()
        assertEquals(5, limits.wrongPins)
        val wrong = if (phone.server.pin == "000000") "000001" else "000000"
        repeat(4) {
            assertEquals(403, phone.pin(wrong)!!.status)
            assertTrue(phone.server.isOpen)
        }
        assertEquals(0, handoffs.changes.get())
        val fifth = phone.pin(wrong)!!
        assertEquals(403, fifth.status)
        assertTrue(fifth.toString(), fifth.body.contains("Too many wrong PINs were typed, so the handoff is closed."))
        assertFalse(fifth.body.contains("<form"))
        assertClosed(phone)
        assertEquals(1, handoffs.changes.get())
    }

    @Test
    fun whatArrivedBeforeAHandoffClosedCanStillBeTaken() {
        val phone = handoffs.open()
        assertEquals(200, phone.links("https://example.org/app")!!.status)
        phone.server.close()
        assertClosed(phone)
        assertEquals(1, phone.server.waiting())
        assertEquals(listOf(HandoffItem.Link("https://example.org/app")), phone.server.take())
    }

    @Test
    fun theTwoHundredAndFirstRequestClosesTheHandoff() {
        val phone = handoffs.open()
        assertEquals(200, limits.requests)
        repeat(100) { assertEquals("request ${2 * it + 1}", 200, phone.get("/")!!.status) }
        repeat(99) { assertEquals("request ${2 * it + 102}", 404, phone.get("/nothing")!!.status) }
        assertEquals(200, phone.get("/${phone.secret}")!!.status)
        assertTrue(phone.server.isOpen)
        assertEquals(0, handoffs.changes.get())

        val last = phone.get("/${phone.secret}")!!
        assertEquals(429, last.status)
        assertTrue(last.toString(), last.body.contains("This handoff has answered as many requests as it will, so it is closed."))
        assertClosed(phone)
        assertEquals(1, handoffs.changes.get())
    }

    @Test
    fun aConnectionThatSendsNothingCountsAsARequest() {
        val phone = handoffs.open(limits.copy(requests = 3))
        phone.connect().close()
        phone.connect().close()
        assertEquals(200, phone.get("/")!!.status)
        assertEquals(429, phone.get("/")!!.status)
        assertClosed(phone)
    }

    @Test
    fun aHandoffClosesByItselfWhenItsTimeIsOver() {
        val began = System.nanoTime()
        val server = HandoffServer.open(Phone.LOOPBACK, limits.copy(lifeMs = 600), nowMs = { 1_000_000L }, onChange = { handoffs.changes.incrementAndGet() })
        val phone = Phone(server)
        assertEquals(1_000_600L, server.closesAtMs)
        assertEquals(200, phone.get("/${phone.secret}")!!.status)
        assertEquals(200, phone.links("https://example.org/app")!!.status)
        assertTrue(server.isOpen)
        waitFor("the handoff to close") { !server.isOpen }
        val lived = (System.nanoTime() - began) / 1_000_000
        assertTrue("$lived ms", lived in 600..1_500)
        assertClosed(phone)
        assertEquals(2, handoffs.changes.get())
        assertEquals(1, server.take().size)
    }

    @Test
    fun closingEndsARequestThatIsOpenAndFreesThePortAtOnce() {
        val phone = handoffs.open()
        phone.connect().use { waiting ->
            waiting.getOutputStream().write(phone.head("POST", "/${phone.secret}/links", "Content-Type: ${Forms.TYPE}", "Content-Length: 50").toByteArray() + "links=".toByteArray())
            waiting.getOutputStream().flush()
            Thread.sleep(200)
            val began = System.nanoTime()
            phone.server.close()
            assertNull(phone.read(waiting))
            val took = (System.nanoTime() - began) / 1_000_000
            assertTrue("$took ms", took < 500)
        }
        assertClosed(phone)
        ServerSocket(phone.port, 1, Phone.LOOPBACK).close()
        assertEquals(0, phone.server.waiting())
        assertEquals(1, handoffs.changes.get())
        phone.server.close()
        assertEquals(1, handoffs.changes.get())
    }

    @Test
    fun afterClosingNoConnectionIsTakenHoweverSoonItComes() {
        // The fault this guards against needs the thread that accepts to be slow to wake, so every processor is kept busy.
        val stop = AtomicBoolean()
        val busy = List(Runtime.getRuntime().availableProcessors()) {
            Thread {
                var n = 0L
                while (!stop.get()) n += System.nanoTime() and 1
            }.apply {
                isDaemon = true
                start()
            }
        }
        try {
            repeat(500) { round ->
                val phone = handoffs.open()
                assertEquals(200, phone.get("/")!!.status)
                phone.server.close()
                assertThrows("round $round", ConnectException::class.java) { phone.connect().close() }
            }
        } finally {
            stop.set(true)
            busy.forEach { it.join(2_000) }
        }
    }

    @Test
    fun aListenerThatFailsTakesNothingWithIt() {
        val server = HandoffServer.open(Phone.LOOPBACK, limits, onChange = { throw IllegalStateException("the listener failed") })
        try {
            assertEquals(200, Phone(server).links("https://example.org/app")!!.status)
            assertEquals(1, server.waiting())
        } finally {
            server.close()
        }
        assertFalse(server.isOpen)
    }

    @Test
    fun theFifthConnectionWaitsUntilOneOfTheFourIsOver() {
        val phone = handoffs.open(limits.copy(headMs = 4_000))
        assertEquals(4, limits.connections)
        val four = List(4) { phone.connect() }
        try {
            phone.connect(readTimeoutMs = 700).use { fifth ->
                fifth.getOutputStream().write(phone.head("GET", "/${phone.secret}").toByteArray())
                fifth.getOutputStream().flush()
                assertThrows(SocketTimeoutException::class.java) { phone.read(fifth) }
                four[2].close()
                fifth.soTimeout = 3_000
                assertEquals(200, phone.read(fifth)!!.status)
            }
        } finally {
            four.forEach { it.close() }
        }
    }

    @Test
    fun aConnectionThatSendsNothingIsEndedAndMakesRoom() {
        val phone = handoffs.open(limits.copy(headMs = 500))
        val began = System.nanoTime()
        val four = List(4) { phone.connect() }
        try {
            assertEquals(200, phone.get("/${phone.secret}")!!.status)
            val waited = (System.nanoTime() - began) / 1_000_000
            assertTrue("$waited ms", waited in 450..2_000)
            for (idle in four) assertEquals(408, phone.read(idle)!!.status)
        } finally {
            four.forEach { it.close() }
        }
    }

    @Test
    fun aClientThatSendsOneByteASecondIsCutOffAfterTenSeconds() {
        val phone = handoffs.open(HandoffLimits())
        val request = phone.head("GET", "/${phone.secret}", "X-Slow: " + "s".repeat(40)).toByteArray()
        val stop = AtomicBoolean()
        phone.connect(readTimeoutMs = 20_000).use { socket ->
            val writer = Thread {
                try {
                    for (b in request) {
                        if (stop.get()) return@Thread
                        socket.getOutputStream().write(b.toInt())
                        socket.getOutputStream().flush()
                        Thread.sleep(1_000)
                    }
                } catch (_: IOException) {
                    return@Thread
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            val began = System.nanoTime()
            writer.start()
            try {
                val reply = phone.read(socket)!!
                val waited = (System.nanoTime() - began) / 1_000_000
                assertEquals(408, reply.status)
                assertTrue(reply.toString(), reply.body.contains("That request took too long to arrive."))
                assertTrue("$waited ms", waited in 9_900..13_500)
            } finally {
                stop.set(true)
                writer.interrupt()
                writer.join(2_000)
            }
        }
        assertTrue(phone.server.isOpen)
        assertEquals(200, phone.get("/${phone.secret}")!!.status)
    }

    @Test
    fun aSlowBodyIsCutOffToo() {
        val phone = handoffs.open(limits.copy(bodyMs = 600))
        phone.connect().use { socket ->
            val out = socket.getOutputStream()
            out.write("POST /${phone.secret}/links HTTP/1.1\r\nHost: ${phone.host}\r\nContent-Type: ${Forms.TYPE}\r\nContent-Length: 40\r\n\r\n".toByteArray())
            out.flush()
            val began = System.nanoTime()
            val writer = Thread {
                try {
                    for (b in "links=https%3A%2F%2Fexample.org%2Fslowly".toByteArray()) {
                        out.write(b.toInt())
                        out.flush()
                        Thread.sleep(100)
                    }
                } catch (_: IOException) {
                    return@Thread
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            writer.start()
            try {
                assertEquals(408, phone.read(socket)!!.status)
                val waited = (System.nanoTime() - began) / 1_000_000
                assertTrue("$waited ms", waited in 550..2_500)
            } finally {
                writer.interrupt()
                writer.join(2_000)
            }
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aHandoffListensOnOneAddressAndNeverOnAll() {
        for (all in listOf("0.0.0.0", "::", "::1", "224.0.0.1")) {
            assertThrows(all, IllegalArgumentException::class.java) { HandoffServer.open(InetAddress.getByName(all)) }
        }
        val phone = handoffs.open()
        val others = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
        assumeTrue("this machine has no address but the loopback one", others.isNotEmpty())
        for (other in others) {
            assertThrows(other.hostAddress, ConnectException::class.java) {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(other, phone.port), 2_000) }
            }
        }
    }

    @Test
    fun limitsThatMakeNoSenseAreRefused() {
        for (make in listOf<() -> HandoffLimits>(
            { HandoffLimits(lifeMs = 0) }, { HandoffLimits(headMs = 0) }, { HandoffLimits(bodyMs = -1) }, { HandoffLimits(connections = 0) },
            { HandoffLimits(requests = 0) }, { HandoffLimits(wrongPins = 0) }, { HandoffLimits(fileBytes = 0) }, { HandoffLimits(headBytes = 10) },
            { HandoffLimits(fileBytes = Int.MAX_VALUE) }, { HandoffLimits(fileBytes = 4096, waitingBytes = 1024) }, { HandoffLimits(waiting = 0) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { make() }
        }
    }
}
