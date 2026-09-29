package io.github.munzzyy.tern.ui.settings

private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** "https://Codeberg.org/some/repo" becomes "codeberg.org". Null unless it is a plain DNS host name. */
fun normalizeHost(text: String): String? {
    val host = text.trim().lowercase()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('.')
    if (host.length > 253 || '.' !in host) return null
    return host.takeIf { h -> h.split('.').all { HOST_LABEL.matches(it) } }
}

fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

/** A proxy host is typed by hand, so accept a name or an IPv4 address, nothing with spaces or a scheme. */
fun isValidProxyHost(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 253 || t.any { it.isWhitespace() } || "://" in t) return false
    return t.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '[' || it == ']' }
}
