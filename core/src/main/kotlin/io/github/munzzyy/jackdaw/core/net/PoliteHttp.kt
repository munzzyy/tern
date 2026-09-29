package io.github.munzzyy.jackdaw.core.net

import java.net.URI
import java.net.URISyntaxException

/** Adds a User-Agent, honours per-host rate limits, and refuses anything that is not HTTPS. */
class PoliteHttp(
    private val delegate: HttpClient,
    private val limiter: RateLimiter,
    private val userAgent: String,
) : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val host = secureHost(request.url) ?: throw InsecureUrlException(request.url)
        limiter.check(host)

        val hasHeader = { name: String -> request.headers.keys.any { it.equals(name, ignoreCase = true) } }
        val extra = buildMap {
            if (!hasHeader("User-Agent")) put("User-Agent", userAgent)
            if (!hasHeader("Accept-Encoding")) put("Accept-Encoding", "identity")
        }
        val finalRequest = if (extra.isEmpty()) request else request.copy(headers = request.headers + extra)

        val response = delegate.execute(finalRequest)
        val blockedUntilMs = limiter.record(host, response)
        if (blockedUntilMs != null) {
            response.close()
            throw RateLimitedException(host, blockedUntilMs)
        }
        return response
    }

    private fun secureHost(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        return uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
