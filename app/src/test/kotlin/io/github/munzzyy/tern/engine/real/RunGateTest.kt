package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A background run that can reach nothing stops once, and a proxy is asked only where asking stays on the device. */
class RunGateTest {
    private val never = { throw AssertionError("the proxy was asked") }

    @Test
    fun offlineStopsTheRunWithoutAskingTheProxy() {
        assertEquals(RunStop.OFFLINE, RunGate.blocked(online = false, proxyOnDevice = true, answers = never))
        assertEquals(RunStop.OFFLINE, RunGate.blocked(online = false, proxyOnDevice = false, answers = never))
    }

    @Test
    fun aSilentProxyOnThisDeviceStopsTheRun() {
        assertEquals(RunStop.PROXY_SILENT, RunGate.blocked(online = true, proxyOnDevice = true) { false })
        assertNull(RunGate.blocked(online = true, proxyOnDevice = true) { true })
    }

    @Test
    fun withNoProxyOnThisDeviceNothingIsAsked() {
        assertNull(RunGate.blocked(online = true, proxyOnDevice = false, answers = never))
    }

    @Test
    fun anOrbotThatIsNotOnThisDeviceIsNotWaitedFor() = runBlocking {
        var probes = 0
        assertFalse(RunGate.answersInTime({ probes++ > 0 }, orbot = true, start = { false }, waitMs = 2_000, pollMs = 10))
        assertEquals("the proxy is asked once and not again", 1, probes)
    }

    @Test
    fun aSilentOrbotIsGivenAWhileToComeUp() = runBlocking {
        var probes = 0
        assertTrue(RunGate.answersInTime({ ++probes >= 3 }, orbot = true, start = { true }, waitMs = 2_000, pollMs = 10))
        assertFalse("one that never comes up is given up on", RunGate.answersInTime({ false }, orbot = true, start = { true }, waitMs = 50, pollMs = 10))
        var started = false
        assertFalse(RunGate.answersInTime({ false }, orbot = false, start = { started = true; true }, waitMs = 2_000, pollMs = 10))
        assertFalse("a proxy that is not Orbot is not asked to start", started)
    }

    @Test
    fun onlyAProxyOn127001IsOnThisDevice() {
        val custom = Settings(proxy = ProxyMode.CUSTOM)
        assertFalse(RunGate.onDevice(Settings(proxy = ProxyMode.NONE)))
        assertTrue(RunGate.onDevice(Settings(proxy = ProxyMode.ORBOT)))
        assertTrue(RunGate.onDevice(custom.copy(proxyHost = "127.0.0.1")))
        assertTrue(RunGate.onDevice(custom.copy(proxyHost = " LocalHost ")))
        for (elsewhere in listOf("proxy.example.org", "10.0.0.2", "192.168.1.5", "127.0.0.1.example.org")) {
            assertFalse("$elsewhere is not asked", RunGate.onDevice(custom.copy(proxyHost = elsewhere)))
        }
    }

    @Test
    fun onlyTheRunsTheJobStartsAskTheProxyFirst() {
        assertTrue(RunGate.byTheJob(CheckCause.SCHEDULE))
        assertTrue(RunGate.byTheJob(CheckCause.RETRY))
        for (cause in CheckCause.entries - CheckCause.SCHEDULE - CheckCause.RETRY) {
            assertFalse("$cause leaves every app to say why", RunGate.byTheJob(cause))
        }
    }
}
