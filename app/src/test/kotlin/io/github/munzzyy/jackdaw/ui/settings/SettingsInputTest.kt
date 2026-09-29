package io.github.munzzyy.jackdaw.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputTest {
    @Test
    fun hostsAreCutFromWhateverIsPasted() {
        assertEquals("codeberg.org", normalizeHost("https://Codeberg.org/example/app?x=1"))
        assertEquals("api.github.com", normalizeHost(" api.github.com. "))
    }

    @Test
    fun oddHostsAreRefused() {
        assertNull(normalizeHost("localhost"))
        assertNull(normalizeHost("exa mple.org"))
        assertNull(normalizeHost("-bad.example.org"))
        assertNull(normalizeHost("a..b"))
        assertNull(normalizeHost(("a".repeat(63) + ".").repeat(5) + "org"))
    }

    @Test
    fun portsAreInRange() {
        assertEquals(9050, parsePort("9050"))
        assertNull(parsePort("0"))
        assertNull(parsePort("65536"))
        assertNull(parsePort("x"))
    }

    @Test
    fun proxyHosts() {
        assertTrue(isValidProxyHost("127.0.0.1"))
        assertTrue(isValidProxyHost("proxy.example.org"))
        assertFalse(isValidProxyHost("socks5://127.0.0.1"))
        assertFalse(isValidProxyHost("a b"))
        assertFalse(isValidProxyHost(""))
    }
}
