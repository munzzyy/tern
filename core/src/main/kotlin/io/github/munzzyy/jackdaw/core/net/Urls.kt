package io.github.munzzyy.jackdaw.core.net

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

object Urls {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private const val MAX_LENGTH = 4096

    /** True only when [url] is already https as written; unlike [normalize] this never upgrades http. */
    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        val candidate = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

        val port = uri.port
        if (port !in -1..65535) return null
        val defaultPort = port == -1 || port == 443 || (scheme == "http" && port == 80)
        val portPart = if (defaultPort) "" else ":$port"
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""

        return "https://$host$portPart$path$query"
    }

    fun host(url: String): String = try {
        URI(url).host?.lowercase().orEmpty()
    } catch (_: URISyntaxException) {
        ""
    }

    fun segments(url: String): List<String> {
        val path = try {
            URI(url).path
        } catch (_: URISyntaxException) {
            null
        } ?: return emptyList()
        return path.split('/').filter { it.isNotEmpty() }
    }

    fun encodeSegment(text: String): String =
        URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    fun queryParam(url: String, name: String): String? {
        val query = try {
            URI(url).rawQuery
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (decodeOrNull(key) == name) return decodeOrNull(value)
        }
        return null
    }

    fun resolve(base: String, href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("mailto:")) return null

        val baseUri = try {
            URI(base)
        } catch (_: URISyntaxException) {
            return null
        }

        val resolved = try {
            baseUri.resolve(trimmed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!resolved.scheme.equals("https", ignoreCase = true)) return null

        return normalize(resolved.toString())
    }

    private fun decodeOrNull(value: String): String? = try {
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
