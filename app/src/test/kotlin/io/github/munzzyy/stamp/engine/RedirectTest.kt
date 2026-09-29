package io.github.munzzyy.stamp.engine

import com.sun.net.httpserver.HttpServer
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.InsecureUrlException
import io.github.munzzyy.stamp.net.UrlConnectionHttp
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The redirect rules on the JVM's own HttpURLConnection, where localhost and 127.0.0.1 are two host names for one server. */
class RedirectTest {
    private val seen = CopyOnWriteArrayList<Pair<String, String?>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val host = exchange.requestHeaders.getFirst("Host").substringBefore(':')
            seen += "$host${exchange.requestURI}" to exchange.requestHeaders.getFirst("Authorization")
            val next = when (exchange.requestURI.path) {
                "/start" -> "/same"
                "/same" -> "http://localhost:$port/other"
                "/other" -> "http://127.0.0.1:$port/back"
                "/insecure" -> "http://example.org/app.apk"
                else -> null
            }
            if (next != null) {
                exchange.responseHeaders.add("Location", next)
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write("ok".toByteArray())
            }
            exchange.close()
        }
        start()
    }
    private val port: Int get() = server.address.port
    private val http = UrlConnectionHttp(cleartextHostsForTests = setOf("127.0.0.1", "localhost"))

    @After
    fun stop() = server.stop(0)

    @Test
    fun authorizationIsDroppedForTheRestOfTheChainOnceTheHostChanges() {
        assertEquals("ok", http.execute(HttpRequest("http://127.0.0.1:$port/start", authorization = "Bearer t")).text())
        assertEquals(
            listOf("127.0.0.1/start" to "Bearer t", "127.0.0.1/same" to "Bearer t", "localhost/other" to null, "127.0.0.1/back" to null),
            seen.toList(),
        )
    }

    @Test
    fun aHeaderNamedAuthorizationInThePlainHeadersIsNeverSent() {
        http.execute(HttpRequest("http://127.0.0.1:$port/back", headers = mapOf("authorization" to "Bearer leak"))).close()
        assertNull(seen.single().second)
    }

    @Test
    fun cleartextOutsideTheTestHostsIsRefused() {
        try {
            http.execute(HttpRequest("http://127.0.0.1:$port/insecure")).close()
            fail("followed a redirect to http")
        } catch (_: InsecureUrlException) {
            assertEquals(1, seen.size)
        }
    }
}
