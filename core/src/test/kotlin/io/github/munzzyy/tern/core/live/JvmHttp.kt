package io.github.munzzyy.tern.core.live

import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InsecureUrlException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * Plain HTTPS for the live tests: redirects followed by hand, credentials dropped when the host
 * changes, gzip negotiated the way Android's own client does it. [bytesReceived] counts what
 * crossed the wire, before decompression.
 */
class JvmHttp : HttpClient {
    val requests = ArrayList<HttpRequest>()
    var bytesReceived = 0L
        private set

    override fun execute(request: HttpRequest): HttpResponse {
        var url = request.url
        var authorization = request.authorization
        val firstHost = URI(url).host
        repeat(8) {
            if (!url.startsWith("https://", ignoreCase = true)) throw InsecureUrlException(url)
            requests.add(request.copy(url = url, authorization = authorization))
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val negotiate = request.headers.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }
            if (negotiate) connection.setRequestProperty("Accept-Encoding", "gzip")
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            val status = connection.responseCode
            if (status in 300..399 && status != 304) {
                val location = connection.getHeaderField("Location") ?: error("Redirect without a Location")
                connection.disconnect()
                url = URI(url).resolve(location).toString()
                if (!URI(url).host.equals(firstHost, ignoreCase = true)) authorization = null
                return@repeat
            }
            val headers = Headers.of(
                connection.headerFields.filterKeys { it != null }.flatMap { (name, values) -> values.map { name to it } },
            )
            val stream: InputStream = (if (status >= 400) connection.errorStream else connection.inputStream) ?: ByteArrayInputStream(ByteArray(0))
            val counted = Counting(stream)
            val zipped = negotiate && connection.contentEncoding.equals("gzip", ignoreCase = true)
            return HttpResponse(status, headers, if (zipped) GZIPInputStream(counted) else counted, url)
        }
        error("Too many redirects from ${request.url}")
    }

    private inner class Counting(private val source: InputStream) : InputStream() {
        override fun read(): Int = source.read().also { if (it >= 0) bytesReceived++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) bytesReceived += it }

        override fun close() = source.close()
    }
}
