package io.github.munzzyy.tern.net

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InsecureUrlException
import io.github.munzzyy.tern.core.net.Urls
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URL

class TooManyRedirectsException(url: String) : IOException("Too many redirects starting at $url")

/** A server on the internet sent the request on to the device itself or to the network it sits in. */
class LocalRedirectException(url: String) : IOException("Refusing a redirect from $url to an address on the local network")

/**
 * The platform's HttpURLConnection with redirects followed by hand, so every hop is checked for
 * HTTPS and the Authorization header never follows a redirect to another host.
 * [cleartextHostsForTests] lets tests reach a local server over http; it is empty everywhere else.
 */
class UrlConnectionHttp(
    private val proxy: () -> Proxy = { Proxy.NO_PROXY },
    private val cleartextHostsForTests: Set<String> = emptySet(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val isLocal: (host: String) -> Boolean = Urls::isLocal,
    private val proxyAnswers: (Proxy) -> Boolean = { true },
) : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        var url = checked(request.url, request.url)
        val originalHost = url.host.lowercase()
        var authorization = request.authorization
        var method = request.method
        repeat(MAX_REDIRECTS + 1) {
            val connection = open(url, method, request.headers, authorization)
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw silentProxy(e)
            }
            if (status in REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without a Location from $url")
                val next = try {
                    URL(url, location)
                } catch (e: MalformedURLException) {
                    throw IOException("Unreadable redirect from $url", e)
                }
                url = checked(next.toString(), request.url)
                if (isLocal(url.host) && !isLocal(originalHost)) throw LocalRedirectException(request.url)
                if (!url.host.equals(originalHost, ignoreCase = true)) authorization = null
                if (status == 303 && method != "HEAD") method = "GET"
                return@repeat
            }
            return respond(connection, status, url)
        }
        throw TooManyRedirectsException(request.url)
    }

    /** What the socket said matters less than that the proxy was not there, so that is what a failure says then. */
    private fun silentProxy(e: IOException): IOException {
        val via = try {
            proxy()
        } catch (_: IOException) {
            return e
        }
        return if (via.type() == Proxy.Type.SOCKS && !proxyAnswers(via)) ProxySilentException(e) else e
    }

    private fun open(url: URL, method: String, headers: Map<String, String>, authorization: String?): HttpURLConnection {
        val connection = url.openConnection(proxy()) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.requestMethod = method
        for ((name, value) in headers) {
            if (name.equals("Authorization", ignoreCase = true)) continue
            connection.setRequestProperty(name, value)
        }
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        return connection
    }

    private fun respond(connection: HttpURLConnection, status: Int, url: URL): HttpResponse {
        val headers = Headers.of(
            connection.headerFields.orEmpty().flatMap { (name, values) ->
                if (name == null) emptyList() else values.orEmpty().map { name to it }
            },
        )
        val raw: InputStream = try {
            if (status >= 400) connection.errorStream ?: ByteArrayInputStream(ByteArray(0)) else connection.inputStream
        } catch (e: IOException) {
            connection.errorStream ?: run {
                connection.disconnect()
                throw e
            }
        }
        val body = object : FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
        return HttpResponse(status, headers, body, url.toString())
    }

    private fun checked(text: String, origin: String): URL {
        val url = try {
            URL(text)
        } catch (e: MalformedURLException) {
            throw IOException("Not a web address: $text", e)
        }
        val scheme = url.protocol.lowercase()
        if (scheme == "https" && url.host.isNotEmpty()) return url
        if (scheme == "http" && url.host.lowercase() in cleartextHostsForTests) return url
        throw InsecureUrlException(if (text == origin) text else "$text (redirected from $origin)")
    }

    companion object {
        const val MAX_REDIRECTS = 8
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun socks(host: String, port: Int): Proxy =
            Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
    }
}
