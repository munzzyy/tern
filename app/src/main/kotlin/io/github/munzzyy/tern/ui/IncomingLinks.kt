package io.github.munzzyy.tern.ui

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

/**
 * A link that asks for a check: tern://refresh for every app, and Obtainium's
 * obtainium://refresh or obtainium://refresh?id=<package> for every app or one. Null for any
 * other link. A check only reads the sources; it adds, installs and removes nothing.
 */
sealed interface RefreshLink {
    data object All : RefreshLink

    /** Obtainium names an app by its package. */
    data class One(val packageName: String) : RefreshLink
}

fun refreshLink(raw: String?): RefreshLink? {
    if (raw == null || raw.length > MAX_INCOMING_CHARS) return null
    val scheme = raw.substringBefore(':', "").lowercase()
    if (scheme != "tern" && scheme != "obtainium") return null
    val rest = raw.substringAfter(':', "").removePrefix("//")
    if (!rest.substringBefore('?').trimEnd('/').equals("refresh", ignoreCase = true)) return null
    val id = rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")?.let(::decode)
    return if (id.isNullOrBlank()) RefreshLink.All else RefreshLink.One(id.take(255))
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
        "tern" -> ternLink(raw, rest)
        "obtainium" -> obtainiumLink(raw, rest)
        else -> null
    }
}

/** The action a tern:// or obtainium:// link asks for, such as "add". */
private fun actionOf(rest: String): String = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()

/**
 * A link that asks for something neither Tern nor Obtainium's links it takes know. It goes to the
 * Add screen whole, which says that Tern does not know it, instead of being dropped without a word.
 */
private fun unknownLink(raw: String, action: String): String? = raw.takeUnless { action in KNOWN_ACTIONS }

private val KNOWN_ACTIONS = setOf("add", "app", "apps", "refresh")

private fun ternLink(raw: String, rest: String): String? {
    val action = actionOf(rest)
    // An app's settings, as Tern's add page hands them on; the Add screen reads them as it reads Obtainium's.
    if (action == "app" || action == "apps") return raw.takeIf { rest.substringAfter('/', "").isNotEmpty() }
    if (action != "add") return unknownLink(raw, action)
    val query = rest.substringAfter('?', "").substringBefore('#')
    val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
    return decode(encoded)?.takeIf(::isHttps)
}

private fun obtainiumLink(raw: String, rest: String): String? {
    val action = actionOf(rest)
    return when (action) {
        // obtainium://add/<address>, raw or encoded, and obtainium://add?url=<address>.
        "add" -> {
            val payload = if (rest.substring(action.length).startsWith("?")) {
                rest.substringAfter('?').substringBefore('#').split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
            } else {
                rest.substringAfter('/', "")
            }
            if (payload.isEmpty()) return null
            val decoded = if (payload.startsWith(HTTPS, ignoreCase = true)) payload else decode(payload)
            decoded?.takeIf(::isHttps)
        }
        "app", "apps" -> raw.takeIf { rest.substringAfter('/', "").isNotEmpty() }
        else -> unknownLink(raw, action)
    }
}

/**
 * What a phone sent through the handoff, as text for the list of what arrived and for the Add
 * screen. Characters that are not drawn are left out, so that no link can look like another one.
 * Null when nothing is left.
 */
fun fromHandoff(text: String): String? {
    val capped = text.take(MAX_INCOMING_CHARS)
    val kept = StringBuilder(capped.length)
    var i = 0
    while (i < capped.length) {
        val point = capped.codePointAt(i)
        if (isDrawn(point)) kept.appendCodePoint(point)
        i += Character.charCount(point)
    }
    return kept.toString().trim().takeIf { it.isNotEmpty() }
}

private fun isDrawn(point: Int): Boolean = when (Character.getType(point).toByte()) {
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
    -> false
    else -> true
}

private fun isHttps(url: String): Boolean =
    url.startsWith(HTTPS, ignoreCase = true) && url.length > HTTPS.length && url.none { it.isWhitespace() || it.isISOControl() }

private fun decode(s: String): String? = try {
    URLDecoder.decode(s, "UTF-8")
} catch (_: IllegalArgumentException) {
    null
}
