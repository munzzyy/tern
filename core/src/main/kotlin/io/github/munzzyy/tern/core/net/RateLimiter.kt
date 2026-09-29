package io.github.munzzyy.tern.core.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Tracks, per host, when it is safe to send the next request. */
class RateLimiter(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val blockedUntilMs = ConcurrentHashMap<String, Long>()

    @Throws(RateLimitedException::class)
    fun check(host: String) {
        val until = blockedUntilMs[host] ?: return
        val now = nowMs()
        if (now < until) throw RateLimitedException(host, until)
    }

    /** Returns the new block deadline when this response blocked [host], null otherwise. */
    fun record(host: String, response: HttpResponse): Long? {
        val now = nowMs()
        val headers = response.headers

        val retryAfterMs = headers["Retry-After"]?.let { parseRetryAfter(it, now) }
        val github = limitPair(headers, "x-ratelimit-remaining", "x-ratelimit-reset")
        val gitlab = limitPair(headers, "ratelimit-remaining", "ratelimit-reset")
        val remaining = github.first ?: gitlab.first
        val resetMs = github.second ?: gitlab.second

        val refused = response.status == 403 || response.status == 429 || response.status == 503
        val blocked = response.status == 429 || remaining == 0L || (refused && retryAfterMs != null)
        if (!blocked) return null

        val target = retryAfterMs ?: resetMs?.let { onOurClock(it, headers, now) } ?: (now + DEFAULT_BLOCK_MS)
        val capped = minOf(target, now + MAX_BLOCK_MS)
        blockedUntilMs[host] = capped
        return capped
    }

    /** A reset time is on the server's clock; a phone whose clock is off would wait hours too long or not at all. */
    private fun onOurClock(resetMs: Long, headers: Headers, now: Long): Long {
        val serverNow = headers["Date"]?.let { parseHttpDate(it) } ?: return resetMs
        return now + (resetMs - serverNow).coerceAtLeast(0)
    }

    private fun limitPair(headers: Headers, remainingName: String, resetName: String): Pair<Long?, Long?> {
        val remaining = headers[remainingName]?.toLongOrNull()
        val reset = headers[resetName]?.toLongOrNull()?.let { it * 1000 }
        return remaining to reset
    }

    private fun parseRetryAfter(value: String, now: Long): Long? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { seconds -> return now + (seconds.coerceIn(0, MAX_BLOCK_MS / 1000) * 1000) }
        return parseHttpDate(trimmed)
    }

    private fun parseHttpDate(value: String): Long? = try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DEFAULT_BLOCK_MS = 60_000L
        const val MAX_BLOCK_MS = 6 * 60 * 60 * 1000L
    }
}
