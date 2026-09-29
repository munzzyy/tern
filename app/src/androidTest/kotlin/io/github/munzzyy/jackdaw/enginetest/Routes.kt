package io.github.munzzyy.jackdaw.enginetest

import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted answers by exact address in front of another client; everything unscripted goes to [fallback]. */
class Routes(private val fallback: HttpClient) : HttpClient {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    private val routes = ConcurrentHashMap<String, (HttpRequest) -> HttpResponse>()

    fun on(url: String, handler: (HttpRequest) -> HttpResponse): Routes = apply { routes[url] = handler }

    /** A file with real Range answers, the way a download server sends it. */
    fun file(url: String, bytes: ByteArray, headers: List<Pair<String, String>>): Routes = on(url) { request ->
        val base = headers + ("Accept-Ranges" to "bytes") + ("ETag" to "\"f${bytes.size}\"")
        val range = request.headers["Range"]?.let { RANGE.matchEntire(it) }
        when {
            request.method == "HEAD" -> HttpResponse.of(200, ByteArray(0), Headers.of(base + ("Content-Length" to bytes.size.toString())), url)
            range == null -> HttpResponse.of(200, bytes, Headers.of(base + ("Content-Length" to bytes.size.toString())), url)
            else -> {
                val start = range.groupValues[1].toInt()
                val end = (range.groupValues[2].toIntOrNull() ?: (bytes.size - 1)).coerceAtMost(bytes.size - 1)
                val slice = bytes.copyOfRange(start, end + 1)
                HttpResponse.of(206, slice, Headers.of(base + ("Content-Range" to "bytes $start-$end/${bytes.size}")), url)
            }
        }
    }

    fun json(url: String, body: String, vararg headers: Pair<String, String>): Routes =
        on(url) { HttpResponse.of(200, body, Headers.of(listOf("Content-Type" to "application/json") + headers), url) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return routes[request.url]?.invoke(request) ?: fallback.execute(request)
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d*)""")
    }
}
