package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.net.Orbot
import io.github.munzzyy.stamp.net.OrbotWatch
import io.github.munzzyy.stamp.net.ProxyChoice
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbotTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val orbotSetting = Settings(proxy = ProxyMode.ORBOT)

    @After
    fun stop() = scope.cancel()

    private fun read(status: String?, host: String? = Orbot.HOST, port: Int? = 9050, action: String? = Orbot.ACTION_STATUS) =
        Orbot.read(action, status, host, port)

    private fun address(proxy: Proxy): String {
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val at = proxy.address() as InetSocketAddress
        assertTrue(at.isUnresolved)
        return "${at.hostString}:${at.port}"
    }

    private fun waitFor(watch: OrbotWatch, state: OrbotState) = runBlocking { withTimeout(5_000) { watch.state.first { it == state } } }

    @Test
    fun theNamesAreTheOnesOrbotUses() {
        assertEquals("org.torproject.android", Orbot.PACKAGE)
        assertEquals("org.torproject.android.intent.action.START", Orbot.ACTION_START)
        assertEquals("org.torproject.android.intent.action.STATUS", Orbot.ACTION_STATUS)
        assertEquals("org.torproject.android.intent.extra.PACKAGE_NAME", Orbot.EXTRA_PACKAGE_NAME)
        assertEquals("org.torproject.android.intent.extra.STATUS", Orbot.EXTRA_STATUS)
        assertEquals("org.torproject.android.intent.extra.SOCKS_PROXY_HOST", Orbot.EXTRA_SOCKS_PROXY_HOST)
        assertEquals("org.torproject.android.intent.extra.SOCKS_PROXY_PORT", Orbot.EXTRA_SOCKS_PROXY_PORT)
    }

    @Test
    fun everyStatusOfOrbotsIsRead() {
        assertEquals(Orbot.Answer(OrbotState.ON, 9050), read("ON"))
        assertEquals(Orbot.Answer(OrbotState.STARTING, null), read("STARTING"))
        assertEquals(Orbot.Answer(OrbotState.OFF, null), read("OFF"))
        assertEquals(Orbot.Answer(OrbotState.OFF, null), read("STOPPING"))
    }

    @Test
    fun startsSwitchedOffIsOff() {
        assertEquals(Orbot.Answer(OrbotState.OFF, null), read("STARTS_DISABLED", host = null, port = -1))
    }

    @Test
    fun whatIsNoAnswerOfOrbotsIsNotRead() {
        assertNull(read("ON", action = Orbot.ACTION_START))
        assertNull(read("ON", action = "org.torproject.android.intent.action.ERROR"))
        assertNull(read("ON", action = null))
        assertNull(read(null))
        assertNull(read(""))
        assertNull(read("on"))
        assertNull(read(" ON"))
        assertNull(read("UNINITIALIZED"))
        assertNull(read("NOT_INSTALLED"))
        assertNull(read("UNKNOWN"))
    }

    @Test
    fun aPortIsTakenOnlyFromThisDevice() {
        for (host in listOf("10.0.0.1", "localhost", "127.0.0.2", "127.0.0.1 ", "0.0.0.0", "::1", "example.org", "", null)) {
            assertEquals("host $host", Orbot.Answer(OrbotState.ON, null), read("ON", host = host, port = 9050))
        }
    }

    @Test
    fun aPortIsTakenOnlyWhenItIsOne() {
        for (port in listOf(0, -1, 65536, Int.MAX_VALUE, Int.MIN_VALUE, null)) {
            assertEquals("port $port", Orbot.Answer(OrbotState.ON, null), read("ON", port = port))
        }
        assertEquals(Orbot.Answer(OrbotState.ON, 1), read("ON", port = 1))
        assertEquals(Orbot.Answer(OrbotState.ON, 65535), read("ON", port = 65535))
    }

    @Test
    fun aPortIsTakenOnlyWhileOrbotIsOn() {
        for (status in listOf("OFF", "STARTING", "STOPPING", "STARTS_DISABLED")) {
            assertNull(status, read(status, port = 9150)!!.port)
        }
    }

    @Test
    fun thePortOrbotReportedIsUsedAndOtherwiseItsOwn() {
        assertEquals("127.0.0.1:9050", address(ProxyChoice.of(orbotSetting)))
        assertEquals("127.0.0.1:9050", address(ProxyChoice.of(orbotSetting, null)))
        assertEquals("127.0.0.1:9150", address(ProxyChoice.of(orbotSetting, 9150)))
    }

    @Test
    fun noPortEverTakesOrbotsProxyOffThisDeviceOrMakesItDirect() {
        for (port in listOf(0, -1, 65536, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("port $port", "127.0.0.1:9050", address(ProxyChoice.of(orbotSetting, port)))
        }
        val elsewhere = orbotSetting.copy(proxyHost = "10.0.0.1", proxyPort = 1080)
        assertEquals("127.0.0.1:9150", address(ProxyChoice.of(elsewhere, 9150)))
    }

    @Test
    fun thePortOrbotReportedMeansNothingToTheOtherSettings() {
        assertSame(Proxy.NO_PROXY, ProxyChoice.of(Settings(), 9150))
        val own = Settings(proxy = ProxyMode.CUSTOM, proxyHost = "proxy.example.org", proxyPort = 1080)
        assertEquals("proxy.example.org:1080", address(ProxyChoice.of(own, 9150)))
    }

    @Test
    fun whenOrbotIsNotThereNothingIsSent() {
        val sent = AtomicInteger()
        val watch = OrbotWatch(scope, installed = { false }, send = { sent.incrementAndGet() })
        assertEquals(OrbotState.UNKNOWN, watch.state.value)
        watch.ask()
        assertEquals(OrbotState.NOT_INSTALLED, watch.state.value)
        assertEquals(0, sent.get())
    }

    @Test
    fun whatOrbotAnswersIsPublished() {
        lateinit var watch: OrbotWatch
        watch = OrbotWatch(scope, installed = { true }, send = { watch.heard(Orbot.Answer(OrbotState.ON, 9150)) })
        watch.ask()
        waitFor(watch, OrbotState.ON)
        assertEquals(9150, watch.port)
    }

    @Test
    fun whileOrbotIsStartingItIsAskedAgainUntilItIsOn() {
        val sent = AtomicInteger()
        lateinit var watch: OrbotWatch
        watch = OrbotWatch(
            scope, installed = { true }, answerWithinMs = 2_000, askAgainAfterMs = 20,
            send = { watch.heard(if (sent.incrementAndGet() < 3) Orbot.Answer(OrbotState.STARTING, null) else Orbot.Answer(OrbotState.ON, 9050)) },
        )
        watch.ask()
        waitFor(watch, OrbotState.ON)
        assertEquals(3, sent.get())
        Thread.sleep(200)
        assertEquals("it was asked again after it said it is on", 3, sent.get())
    }

    @Test
    fun anOrbotThatKeepsStartingIsNotAskedForever() {
        val sent = AtomicInteger()
        lateinit var watch: OrbotWatch
        watch = OrbotWatch(
            scope, installed = { true }, answerWithinMs = 2_000, askAgainAfterMs = 5, askAgainTimes = 4,
            send = {
                sent.incrementAndGet()
                watch.heard(Orbot.Answer(OrbotState.STARTING, null))
            },
        )
        watch.ask()
        Thread.sleep(500)
        assertEquals(4, sent.get())
        assertEquals(OrbotState.STARTING, watch.state.value)
    }

    @Test
    fun anOrbotThatSaysNothingAndHasNoProxyRunningIsOff() {
        val watch = OrbotWatch(scope, installed = { true }, send = {}, answerWithinMs = 100, probe = { false })
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        assertEquals(OrbotState.ON, watch.state.value)
        watch.ask()
        waitFor(watch, OrbotState.OFF)
        assertNull(watch.port)
    }

    @Test
    fun aRequestThatCannotBeSentLeavesTheProxyToSay() {
        val watch = OrbotWatch(scope, installed = { true }, send = { throw SecurityException("refused") }, answerWithinMs = 5_000, probe = { false })
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        watch.ask()
        waitFor(watch, OrbotState.OFF)
        assertNull(watch.port)
    }

    /** Orbot from 2026-09-15 on: it answers no request, and its proxy is the only thing to ask. */
    @Test
    fun anOrbotThatSaysNothingIsOnWhenItsProxyAnswers() {
        val sent = AtomicInteger()
        val asked = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val watch = OrbotWatch(scope, installed = { true }, send = { sent.incrementAndGet() }, answerWithinMs = 100, probe = { asked += it; it == 9050 })
        watch.ask()
        waitFor(watch, OrbotState.ON)
        assertEquals(9050, watch.port)
        assertEquals(listOf(9050), asked.toList())
        assertEquals("a proxy that answers needs no request", 0, sent.get())
    }

    @Test
    fun aProxyThatStartsToAnswerAfterTheRequestCountsToo() {
        val probes = AtomicInteger()
        val watch = OrbotWatch(scope, installed = { true }, send = {}, answerWithinMs = 100, probe = { probes.incrementAndGet() > 1 })
        watch.ask()
        waitFor(watch, OrbotState.ON)
        assertEquals(9050, watch.port)
    }

    @Test
    fun thePortAnOlderOrbotNamedIsAskedFirstAndItsUsualOneAfterIt() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val watch = OrbotWatch(scope, installed = { true }, send = {}, answerWithinMs = 100, probe = { asked += it; it == 9050 })
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        watch.ask()
        waitFor(watch, OrbotState.ON)
        runBlocking { withTimeout(5_000) { while (watch.port != 9050) kotlinx.coroutines.delay(10) } }
        assertEquals(listOf(9150, 9050), asked.toList())
    }

    @Test
    fun onlyAPortOnThisDeviceIsEverAsked() {
        assertEquals(false, io.github.munzzyy.stamp.net.ProxyProbe.answers(0))
        assertEquals(false, io.github.munzzyy.stamp.net.ProxyProbe.answers(65536))
        val nobody = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(false, io.github.munzzyy.stamp.net.ProxyProbe.answers(nobody, withinMs = 500))
    }

    @Test
    fun aProxyThatSaysHelloTheWayOfSocksAnswersAndAnythingElseDoesNot() {
        fun server(reply: ByteArray): Pair<java.net.ServerSocket, java.util.concurrent.CopyOnWriteArrayList<List<Int>>> {
            val heard = java.util.concurrent.CopyOnWriteArrayList<List<Int>>()
            val socket = java.net.ServerSocket(0, 5, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            Thread {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream()
                        heard += listOf(input.read(), input.read(), input.read())
                        client.getOutputStream().apply { write(reply); flush() }
                    }
                } catch (_: java.io.IOException) {
                }
            }.apply { isDaemon = true }.start()
            return socket to heard
        }
        val (socks, heard) = server(byteArrayOf(5, 0))
        socks.use { assertEquals(true, io.github.munzzyy.stamp.net.ProxyProbe.answers(it.localPort)) }
        assertEquals(listOf(listOf(5, 1, 0)), heard.toList())
        val (web, _) = server("HT".toByteArray())
        web.use { assertEquals(false, io.github.munzzyy.stamp.net.ProxyProbe.answers(it.localPort)) }
        val (wantsLogin, _) = server(byteArrayOf(5, 2))
        wantsLogin.use { assertEquals(false, io.github.munzzyy.stamp.net.ProxyProbe.answers(it.localPort)) }
    }

    @Test
    fun whatOrbotSaysByItselfIsPublishedToo() {
        val watch = OrbotWatch(scope, installed = { true }, send = {})
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        assertEquals(OrbotState.ON, watch.state.value)
        assertEquals(9150, watch.port)
        watch.heard(Orbot.Answer(OrbotState.OFF, null))
        assertEquals(OrbotState.OFF, watch.state.value)
        assertNull(watch.port)
    }

    @Test
    fun whileOrbotIsNotInstalledNoAnswerIsTaken() {
        val watch = OrbotWatch(scope, installed = { false }, send = {})
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        assertEquals(OrbotState.UNKNOWN, watch.state.value)
        assertNull(watch.port)
        watch.ask()
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        assertEquals(OrbotState.NOT_INSTALLED, watch.state.value)
        assertNull(watch.port)
    }

    @Test
    fun anAnswerThatComesRightAfterTheQuestionCounts() {
        val watch = OrbotWatch(scope, installed = { true }, send = {}, answerWithinMs = 300)
        watch.ask()
        Thread.sleep(50)
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        Thread.sleep(600)
        assertEquals(OrbotState.ON, watch.state.value)
        assertEquals(9150, watch.port)
    }

    @Test
    fun aRemovedOrbotIsNotInstalledAndAnInstalledOneIsUnknown() {
        var there = true
        val watch = OrbotWatch(scope, installed = { there }, send = {})
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        there = false
        watch.packageChanged()
        assertEquals(OrbotState.NOT_INSTALLED, watch.state.value)
        assertNull(watch.port)
        there = true
        watch.packageChanged()
        assertEquals(OrbotState.UNKNOWN, watch.state.value)
    }

    @Test
    fun anUpdateOfOrbotKeepsWhatItSaid() {
        val watch = OrbotWatch(scope, installed = { true }, send = {})
        watch.heard(Orbot.Answer(OrbotState.ON, 9150))
        watch.packageChanged()
        assertEquals(OrbotState.ON, watch.state.value)
        assertEquals(9150, watch.port)
    }
}
