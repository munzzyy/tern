package io.github.munzzyy.stamp.enginetest

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.MainActivity
import io.github.munzzyy.stamp.core.interop.StampExport
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.engine.Handoff
import io.github.munzzyy.stamp.engine.Received
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
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
    private class Reply(val status: Int, val location: String?)

    private fun send(handoff: Handoff, request: String): Reply {
        val address = URI(handoff.address)
        val text = Socket().use { socket ->
            socket.connect(InetSocketAddress(address.host, address.port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.replace("HOST", "${address.host}:${address.port}").toByteArray())
            socket.getOutputStream().flush()
            String(socket.getInputStream().readBytes())
        }
        val head = text.substringBefore("\r\n\r\n").split("\r\n")
        return Reply(head[0].split(' ')[1].toInt(), head.firstOrNull { it.startsWith("Location: ") }?.removePrefix("Location: "))
    }

    private fun form(handoff: Handoff, path: String, body: String): Reply =
        send(handoff, "POST $path HTTP/1.1\r\nHost: HOST\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")

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
            val page = form(handoff, "/pin", "pin=${handoff.code}").location!!
            assertEquals(200, form(handoff, "$page/links", "links=https%3A%2F%2Fgithub.com%2Fexample%2Fwren").status)
            waitUntil(5_000, "the link to be counted") { h.engine.handoff.value?.waiting == 1 }
            assertEquals(listOf<Received>(Received.Link("https://github.com/example/wren")), h.engine.takeReceived())
            assertEquals(0, h.engine.handoff.value!!.waiting)
            assertEquals(emptyList<String>(), h.engine.apps.value.map { it.config.name })

            h.engine.closeHandoff()
            assertNull(h.engine.handoff.value)
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
                assertThrows(ConnectException::class.java) { send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n") }
                assertEquals("Stamp has to be on the screen while it takes links from a phone. Open Stamp and try again.", runBlocking { h.engine.openHandoff() }?.message)

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
                assertEquals(200, send(handoff, "GET / HTTP/1.1\r\nHost: HOST\r\n\r\n").status)
            }
        }
    }

    @Test
    fun aFileThatWasReceivedIsImportedLikeAnyOther() = runBlocking {
        val apps = listOf("Wren", "Dunnock").map { AppConfig(it.lowercase(), SourceSpec(SourceTypes.GITHUB, "https://github.com/example/${it.lowercase()}"), it) }
        Harness("handoff-import").use { h ->
            val summary = h.engine.importReceived(Received.ExportFile("stamp-apps.json", StampExport.write(apps, 0, "test").toByteArray()))
            assertEquals(2, summary.added)
            assertEquals(listOf("Dunnock", "Wren"), h.engine.apps.value.map { it.config.name })
            assertEquals(2, h.engine.importReceived(Received.ExportFile("again.json", StampExport.write(apps, 0, "test").toByteArray())).alreadyPresent)
        }
    }
}
