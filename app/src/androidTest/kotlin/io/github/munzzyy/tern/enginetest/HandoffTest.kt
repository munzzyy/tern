package io.github.munzzyy.tern.enginetest

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.MainActivity
import io.github.munzzyy.tern.core.handoff.Seal
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.Received
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The part of the handoff that needs Android: which address the device has on its network, and
 * that leaving the screen ends the handoff. It needs a device that is on Wi-Fi or on a cable,
 * which the emulator images are. A failure names the address, which is the fact to look at.
 */
@RunWith(AndroidJUnit4::class)
class HandoffTest {
    private class Reply(val status: Int)

    private fun send(handoff: Handoff, request: String): Reply {
        val address = URI(handoff.address)
        val text = Socket().use { socket ->
            socket.connect(InetSocketAddress(address.host, address.port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.replace("HOST", "${address.host}:${address.port}").toByteArray())
            socket.getOutputStream().flush()
            String(socket.getInputStream().readBytes())
        }
        return Reply(text.substringBefore("\r\n").split(' ')[1].toInt())
    }

    /** What the page does in the browser of the phone: seal with the code the screen shows, and post. */
    private fun links(handoff: Handoff, links: String, code: String = handoff.code): Reply {
        val sealed = Seal(code.replace(" ", "")).seal(Seal.LINKS, ByteArray(Seal.NONCE).also(SecureRandom()::nextBytes), links.toByteArray())
        val body = "sealed=" + Seal.text(sealed)
        return send(handoff, "POST /send HTTP/1.1\r\nHost: HOST\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")
    }

    private fun open(h: Harness): Handoff {
        assertNull(runBlocking { h.engine.openHandoff() })
        return h.engine.handoff.value!!
    }

    @Test
    fun theHandoffListensOnThePrivateAddressOfTheDeviceAndNowhereElse() {
        Harness("handoff-address").use { h ->
            val handoff = open(h)
            val host = URI(handoff.address).host
            assertTrue(handoff.address, Regex("(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*").matches(host))
            assertEquals(handoff.address, 200, send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n").status)
            assertThrows(handoff.address, ConnectException::class.java) {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", URI(handoff.address).port), 2_000) }
            }
        }
    }

    @Test
    fun whatArrivesWaitsUntilItIsTakenAndClosingFreesThePort() {
        Harness("handoff-door").use { h ->
            val handoff = open(h)
            assertTrue(handoff.code, Regex("[a-z2-7]{4}( [a-z2-7]{4}){4}").matches(handoff.code))
            val other = handoff.code.dropLast(1) + if (handoff.code.endsWith('a')) 'b' else 'a'
            assertEquals(403, links(handoff, "https://github.com/example/wren", other).status)
            assertEquals(0, h.engine.handoff.value!!.waiting)
            assertEquals(200, links(handoff, "https://github.com/example/wren").status)
            waitUntil(5_000, "the link to be counted") { h.engine.handoff.value?.waiting == 1 }
            assertEquals(listOf<Received>(Received.Link("https://github.com/example/wren")), h.engine.takeReceived())
            assertEquals(0, h.engine.handoff.value!!.waiting)
            assertEquals(emptyList<String>(), h.engine.apps.value.map { it.config.name })

            assertNull(h.engine.handoffEnd.value)
            h.engine.closeHandoff()
            assertNull(h.engine.handoff.value)
            assertEquals(HandoffEnd.CLOSED, h.engine.handoffEnd.value)
            assertThrows(ConnectException::class.java) { send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
        }
    }

    @Test
    fun leavingTheScreenEndsTheHandoff() {
        Harness("handoff-screen").use { h ->
            ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java)).use { screen ->
                val handoff = open(h)
                screen.moveToState(Lifecycle.State.CREATED)
                waitUntil(5_000, "the handoff to end") { h.engine.handoff.value == null }
                assertEquals(HandoffEnd.LEFT_SCREEN, h.engine.handoffEnd.value)
                assertThrows(ConnectException::class.java) { send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
                assertEquals("Tern has to be on the screen while it takes links from a phone. Open Tern and try again.", runBlocking { h.engine.openHandoff() }?.message)

                screen.moveToState(Lifecycle.State.RESUMED)
                open(h)
            }
        }
    }

    @Test
    fun turningTheScreenLeavesTheHandoffOpen() {
        Harness("handoff-turn").use { h ->
            ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java)).use { screen ->
                val handoff = open(h)
                screen.recreate()
                assertEquals(handoff, h.engine.handoff.value)
                assertNull(h.engine.handoffEnd.value)
                assertEquals(200, send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n").status)
            }
        }
    }

    @Test
    fun aFileThatWasReceivedIsImportedLikeAnyOther() = runBlocking {
        val apps = listOf("Wren", "Dunnock").map { AppConfig(it.lowercase(), SourceSpec(SourceTypes.GITHUB, "https://github.com/example/${it.lowercase()}"), it) }
        Harness("handoff-import").use { h ->
            val summary = h.engine.importReceived(Received.ExportFile("tern-apps.json", TernExport.write(apps, 0, "test").toByteArray()))
            assertEquals(2, summary.added)
            assertEquals(listOf("Dunnock", "Wren"), h.engine.apps.value.map { it.config.name })
            assertEquals(2, h.engine.importReceived(Received.ExportFile("again.json", TernExport.write(apps, 0, "test").toByteArray())).alreadyPresent)
        }
    }
}
