package io.github.munzzyy.tern.core.net

import java.io.IOException

/**
 * A file's size asked of the server that holds it, for a file whose source names none: a request
 * for its first byte, whose answer says how long the whole file is. Nothing more is read.
 */
object RemoteSize {
    private val CONTENT_RANGE = Regex("""^bytes\s+\d+-\d+/(\d+)$""", RegexOption.IGNORE_CASE)

    /**
     * The size of the file at [url], or null when the server does not say. [authorization] goes
     * only where [HttpRequest.authorization] lets it, and [headers] are those the source asks for.
     */
    @Throws(IOException::class)
    fun of(http: HttpClient, url: String, authorization: String? = null, headers: Map<String, String> = emptyMap()): Long? {
        val request = HttpRequest(url, headers = headers + mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity"), authorization = authorization)
        return http.execute(request).use { sizeFrom(it.status, it.headers) }
    }

    /** What an answer of [status] with [headers] says of the whole file: the total of a range, or the length of a server that sends it all. */
    fun sizeFrom(status: Int, headers: Headers): Long? {
        val size = when (status) {
            206 -> headers["Content-Range"]?.trim()?.let { CONTENT_RANGE.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()
            200 -> headers["Content-Length"]?.trim()?.toLongOrNull()
            else -> null
        }
        return size?.takeIf { it > 0 }
    }
}
