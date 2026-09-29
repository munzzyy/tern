package io.github.munzzyy.stamp.engine

import com.sun.net.httpserver.HttpServer
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.net.ProxyChoice
import io.github.munzzyy.stamp.net.ProxyDoor
import io.github.munzzyy.stamp.net.ProxySettingsException
import io.github.munzzyy.stamp.net.UrlConnectionHttp
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Which proxy a setting gives, and that a request fails when its proxy does not answer. The
 * requests run on the JVM's own HttpURLConnection; what Android's does is tested on a device.
 */
class ProxyChoiceTest {
    private val seen = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            seen += exchange.requestURI.path
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("ok".toByteArray())
            exchange.close()
        }
        start()
    }
    private val address: String get() = "http://127.0.0.1:${server.address.port}/file"

    @After
    fun stop() = server.stop(0)

    private fun nobodyListens(): Int = ServerSocket(0).use { it.localPort }

    private fun over(proxy: () -> Proxy) = UrlConnectionHttp(proxy, cleartextHostsForTests = setOf("127.0.0.1"), connectTimeoutMs = 5_000, readTimeoutMs = 5_000)

    private fun socksAddress(proxy: Proxy): InetSocketAddress {
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        return proxy.address() as InetSocketAddress
    }

    @Test
    fun withoutAProxyARequestGoesDirect() {
        assertSame(Proxy.NO_PROXY, ProxyChoice.of(Settings()))
        assertSame(Proxy.NO_PROXY, ProxyChoice.of(Settings(proxy = ProxyMode.NONE, proxyHost = "proxy.example.org", proxyPort = 1080)))
    }

    @Test
    fun orbotIsASocksProxyOnThisDevice() {
        val at = socksAddress(ProxyChoice.of(Settings(proxy = ProxyMode.ORBOT, proxyHost = "proxy.example.org", proxyPort = 1080)))
        assertEquals("127.0.0.1", at.hostString)
        assertEquals(9050, at.port)
        assertTrue(at.isUnresolved)
    }

    /** The name is one every machine can look up without a network, so an address that was looked up shows. */
    @Test
    fun aProxyOfTheUsersOwnIsNotResolvedHere() {
        val at = socksAddress(ProxyChoice.of(Settings(proxy = ProxyMode.CUSTOM, proxyHost = " localhost ", proxyPort = 1080)))
        assertEquals("localhost", at.hostString)
        assertEquals(1080, at.port)
        assertTrue(at.isUnresolved)
    }

    @Test
    fun aSettingThatNamesNoProxyFailsAndNeverGoesDirect() {
        val hosts = listOf("", " ", "two words", "under_score", "-dash", "dash-", ".dot", "exa‮mple", "host:80", "[::1]", "a/b", "a".repeat(254))
        for (host in hosts) refused(Settings(proxy = ProxyMode.CUSTOM, proxyHost = host, proxyPort = 1080), "host '$host'")
        for (port in listOf(0, -1, 65536, Int.MAX_VALUE)) refused(Settings(proxy = ProxyMode.CUSTOM, proxyHost = "127.0.0.1", proxyPort = port), "port $port")
    }

    private fun refused(settings: Settings, what: String) {
        try {
            val proxy = ProxyChoice.of(settings)
            fail("$what gave $proxy")
        } catch (e: IOException) {
            assertTrue("$what failed as ${e.javaClass.simpleName}", e is ProxySettingsException)
        }
    }

    @Test
    fun aDoorWithNobodyToAskFails() {
        val door = ProxyDoor()
        try {
            door.proxy()
            fail("a door with nobody to ask gave a proxy")
        } catch (_: ProxySettingsException) {
        }
        val socks = UrlConnectionHttp.socks("127.0.0.1", 9050)
        door.follow { socks }
        assertSame(socks, door.proxy())
        door.follow { throw ProxySettingsException("refused") }
        try {
            door.proxy()
            fail("a refusal was swallowed")
        } catch (e: ProxySettingsException) {
            assertEquals("refused", e.message)
        }
    }

    @Test
    fun aRequestWhileTheDoorHasNobodyToAskFailsAndReachesNobody() {
        val http = over(ProxyDoor()::proxy)
        try {
            http.execute(HttpRequest(address)).close()
            fail("the request went out")
        } catch (_: ProxySettingsException) {
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aRequestThroughAProxyThatDoesNotAnswerFailsAndReachesNobody() {
        assertEquals("ok", over { Proxy.NO_PROXY }.execute(HttpRequest(address)).text())
        assertEquals(listOf("/file"), seen.toList())
        seen.clear()

        val port = nobodyListens()
        try {
            over { UrlConnectionHttp.socks("127.0.0.1", port) }.execute(HttpRequest(address)).close()
            fail("the request went out")
        } catch (_: IOException) {
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aSettingThatNamesNoProxyFailsTheRequestAndReachesNobody() {
        val http = over { ProxyChoice.of(Settings(proxy = ProxyMode.CUSTOM, proxyHost = "not a host", proxyPort = 1080)) }
        try {
            http.execute(HttpRequest(address)).close()
            fail("the request went out")
        } catch (_: ProxySettingsException) {
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aChangeOfTheSettingHoldsFromTheNextRequestOn() {
        var settings = Settings()
        val http = over { ProxyChoice.of(settings) }
        assertEquals("ok", http.execute(HttpRequest(address)).text())
        settings = Settings(proxy = ProxyMode.CUSTOM, proxyHost = "127.0.0.1", proxyPort = nobodyListens())
        try {
            http.execute(HttpRequest(address)).close()
            fail("the request went out")
        } catch (_: IOException) {
        }
        settings = Settings()
        assertEquals("ok", http.execute(HttpRequest(address)).text())
        assertEquals(listOf("/file", "/file"), seen.toList())
    }
}
