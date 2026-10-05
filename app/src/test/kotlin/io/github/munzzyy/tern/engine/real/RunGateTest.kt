package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings
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
        assertEquals(RunGate.Block.OFFLINE, RunGate.blocked(online = false, proxyOnDevice = true, answers = never))
        assertEquals(RunGate.Block.OFFLINE, RunGate.blocked(online = false, proxyOnDevice = false, answers = never))
    }

    @Test
    fun aSilentProxyOnThisDeviceStopsTheRun() {
        assertEquals(RunGate.Block.PROXY_SILENT, RunGate.blocked(online = true, proxyOnDevice = true) { false })
        assertNull(RunGate.blocked(online = true, proxyOnDevice = true) { true })
    }

    @Test
    fun withNoProxyOnThisDeviceNothingIsAsked() {
        assertNull(RunGate.blocked(online = true, proxyOnDevice = false, answers = never))
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
        assertTrue(RunGate.asksProxyFirst(CheckCause.SCHEDULE))
        assertTrue(RunGate.asksProxyFirst(CheckCause.RETRY))
        for (cause in CheckCause.entries - CheckCause.SCHEDULE - CheckCause.RETRY) {
            assertFalse("$cause leaves every app to say why", RunGate.asksProxyFirst(cause))
        }
    }
}
