package io.github.munzzyy.tern.core.testing

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import java.io.IOException

/**
 * Scripted HTTP for tests. An unscripted URL fails the test instead of returning 404, so a source
 * that starts calling a new endpoint is noticed.
 */
class FakeHttp : HttpClient {
    val requests = ArrayList<HttpRequest>()
    private val routes = LinkedHashMap<String, (HttpRequest) -> HttpResponse>()

    fun on(url: String, handler: (HttpRequest) -> HttpResponse): FakeHttp = apply { routes[url] = handler }

    fun text(url: String, body: String, status: Int = 200, headers: Headers = Headers.EMPTY): FakeHttp =
        on(url) { HttpResponse.of(status, body, headers, url) }

    fun bytes(url: String, body: ByteArray, headers: Headers = Headers.EMPTY): FakeHttp = on(url) { request ->
        val range = request.headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        if (range == null) {
            HttpResponse.of(200, body, headers, url)
        } else {
            val (from, to) = parseRange(range, body.size)
            val slice = body.copyOfRange(from, to + 1)
            HttpResponse.of(206, slice, Headers.of(headers.toList() + ("Content-Range" to "bytes $from-$to/${body.size}")), url)
        }
    }

    fun resource(url: String, path: String, headers: Headers = Headers.EMPTY): FakeHttp = text(url, Fixtures.text(path), headers = headers)

    fun requestsTo(url: String): List<HttpRequest> = requests.filter { it.url == url }

    override fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        val handler = routes[request.url] ?: throw IOException("FakeHttp has no route for ${request.method} ${request.url}")
        return handler(request)
    }

    private fun parseRange(header: String, size: Int): Pair<Int, Int> {
        val spec = header.removePrefix("bytes=")
        if (spec.startsWith("-")) {
            val suffix = spec.drop(1).toInt().coerceAtMost(size)
            return (size - suffix) to (size - 1)
        }
        val from = spec.substringBefore('-').toInt()
        val to = spec.substringAfter('-').ifEmpty { (size - 1).toString() }.toInt().coerceAtMost(size - 1)
        return from to to
    }
}

object Fixtures {
    fun text(path: String): String = String(bytes(path), Charsets.UTF_8)

    fun bytes(path: String): ByteArray =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$path") ?: error("Missing fixture $path")).use { it.readBytes() }
}
