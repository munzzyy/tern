package io.github.munzzyy.jackdaw.ui

import java.net.URLDecoder

const val MAX_INCOMING_CHARS = 8_000

private const val HTTPS = "https://"
private val URL_END = setOf(' ', '\n', '\r', '\t', '<', '>', '"', '\'', '`')
private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

/**
 * Turns an incoming intent into text for the Add screen, or null when there is nothing usable.
 * The result is only ever shown in a text field and passed to detect; nothing is added or installed from here.
 */
fun incomingAddInput(action: String?, data: String?, sharedText: String?): String? = when (action) {
    ACTION_SEND -> sharedText?.let(::fromSharedText)
    ACTION_VIEW -> data?.let(::fromLink)
    else -> null
}

const val ACTION_SEND = "android.intent.action.SEND"
const val ACTION_VIEW = "android.intent.action.VIEW"

fun fromSharedText(text: String): String? {
    val capped = text.take(MAX_INCOMING_CHARS)
    return firstHttpsUrl(capped) ?: capped.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
        .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
}

fun firstHttpsUrl(text: String): String? {
    val start = text.indexOf(HTTPS, ignoreCase = true)
    if (start < 0) return null
    var end = start + HTTPS.length
    while (end < text.length && text[end] !in URL_END && !text[end].isWhitespace()) end++
    var url = text.substring(start, end)
    while (url.isNotEmpty() && url.last() in TRAILING_PUNCTUATION && !balancedClose(url)) url = url.dropLast(1)
    return url.takeIf { it.length > HTTPS.length }
}

private fun balancedClose(url: String): Boolean {
    val close = url.last()
    val open = when (close) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> return false
    }
    return url.count { it == open } >= url.count { it == close }
}

fun fromLink(raw: String): String? {
    if (raw.length > MAX_INCOMING_CHARS) return null
    val scheme = raw.substringBefore(':', "").lowercase()
    val rest = raw.substringAfter(':', "").removePrefix("//")
    return when (scheme) {
        "jackdaw" -> jackdawLink(rest)
        "obtainium" -> obtainiumLink(raw, rest)
        else -> null
    }
}

private fun jackdawLink(rest: String): String? {
    if (!rest.substringBefore('?').trimEnd('/').equals("add", ignoreCase = true)) return null
    val query = rest.substringAfter('?', "").substringBefore('#')
    val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
    return decode(encoded)?.takeIf(::isHttps)
}

private fun obtainiumLink(raw: String, rest: String): String? {
    val kind = rest.substringBefore('/').lowercase()
    val payload = rest.substringAfter('/', "")
    if (payload.isEmpty()) return null
    return when (kind) {
        "add" -> {
            val decoded = if (payload.startsWith(HTTPS, ignoreCase = true)) payload else decode(payload)
            decoded?.takeIf(::isHttps)
        }
        "app", "apps" -> raw
        else -> null
    }
}

private fun isHttps(url: String): Boolean =
    url.startsWith(HTTPS, ignoreCase = true) && url.length > HTTPS.length && url.none { it.isWhitespace() || it.isISOControl() }

private fun decode(s: String): String? = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: IllegalArgumentException) {
    null
}
