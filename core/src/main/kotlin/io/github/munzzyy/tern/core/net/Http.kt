package io.github.munzzyy.tern.core.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** Sent only to the host of [url]; an implementation must drop it when a redirect leaves that host. */
    val authorization: String? = null,
    /** False answers a redirect with the redirect itself, so a source can read where it points without going there. */
    val followRedirects: Boolean = true,
    /** Sent as the request body, with the Content-Type given in [headers]. Only for methods that take one, such as POST. */
    val body: ByteArray? = null,
) {
    /** Without the body, which may be long and is not worth a log line. */
    override fun toString(): String = "HttpRequest(method=$method, url=$url, headers=${headers.keys}, body=${body?.size ?: 0} bytes)"

    override fun equals(other: Any?): Boolean = other is HttpRequest && other.url == url && other.method == method &&
        other.headers == headers && other.authorization == authorization && other.followRedirects == followRedirects &&
        (other.body contentEquals body)

    override fun hashCode(): Int = listOf(url, method, headers, authorization, followRedirects).hashCode() * 31 + (body?.contentHashCode() ?: 0)

    companion object {
        /** A POST of [text], encoded as UTF-8, with [contentType]. */
        fun post(url: String, text: String, contentType: String, headers: Map<String, String> = emptyMap()): HttpRequest =
            HttpRequest(url, method = "POST", headers = headers + ("Content-Type" to contentType), body = text.toByteArray(Charsets.UTF_8))
    }
}

class HttpResponse(
    val status: Int,
    val headers: Headers,
    val body: InputStream,
    val url: String,
) : Closeable {
    val isSuccess: Boolean get() = status in 200..299

    val isNotModified: Boolean get() = status == 304

    fun bytes(limit: Int = DEFAULT_LIMIT): ByteArray = use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw BodyTooLargeException(url, limit)
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    fun text(limit: Int = DEFAULT_LIMIT): String = String(bytes(limit), Charsets.UTF_8)

    override fun close() {
        try {
            body.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 8 * 1024 * 1024

        fun of(status: Int, body: String = "", headers: Headers = Headers.EMPTY, url: String = ""): HttpResponse =
            HttpResponse(status, headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), url)

        fun of(status: Int, body: ByteArray, headers: Headers = Headers.EMPTY, url: String = ""): HttpResponse =
            HttpResponse(status, headers, ByteArrayInputStream(body), url)
    }
}

fun interface HttpClient {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

class BodyTooLargeException(val url: String, val limit: Int) : IOException("Response from $url is larger than $limit bytes")

class InsecureUrlException(val url: String) : IOException("Refusing a connection that is not HTTPS: $url")

/** [retryAtMs] is epoch milliseconds, or null when the server gave no reset time. */
class RateLimitedException(val host: String, val retryAtMs: Long?) : IOException("Rate limited by $host")

data class Validator(val etag: String?, val lastModified: String?) {
    val isEmpty: Boolean get() = etag == null && lastModified == null

    fun conditionalHeaders(): Map<String, String> = buildMap {
        etag?.let { put("If-None-Match", it) }
        lastModified?.let { put("If-Modified-Since", it) }
    }

    companion object {
        fun from(headers: Headers): Validator = Validator(headers["ETag"], headers["Last-Modified"])
    }
}

interface ValidatorStore {
    fun get(key: String): Validator?

    fun put(key: String, validator: Validator)

    fun remove(key: String)
}

class InMemoryValidatorStore : ValidatorStore {
    private val map = HashMap<String, Validator>()

    @Synchronized
    override fun get(key: String): Validator? = map[key]

    @Synchronized
    override fun put(key: String, validator: Validator) {
        map[key] = validator
    }

    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }
}
