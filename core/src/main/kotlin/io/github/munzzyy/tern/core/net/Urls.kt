package io.github.munzzyy.tern.core.net

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

    /** [normalize], handed back as a URI for callers that need its parts. */
    fun parseHttps(input: String): URI? = normalize(input)?.let {
        try {
            URI(it)
        } catch (_: URISyntaxException) {
            null
        }
    }

    fun host(url: String): String = try {
        URI(url).host?.lowercase().orEmpty()
    } catch (_: URISyntaxException) {
        ""
    }

    /** The host, and the port behind it when the address names one that is not the usual one. */
    fun authority(url: String): String = try {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty() || uri.port == -1 || uri.port == 443) host else "$host:${uri.port}"
    } catch (_: URISyntaxException) {
        ""
    }

    /**
     * True for a host that can only be the device itself or something on the network it sits in:
     * an address from the loopback, private, link-local or shared ranges, or a name no public
     * server has. It reads the text and nothing else. A name is never looked up for this, because
     * a lookup would tell a name server about it behind the back of a proxy.
     */
    fun isLocal(host: String): Boolean {
        val name = host.trim().lowercase().trimEnd('.')
        if (name.isEmpty()) return true
        if (name.startsWith("[") || ':' in name) return isLocalV6(name.removePrefix("[").removeSuffix("]"))
        if (name[0].isDigit() && name.all { it.isLetterOrDigit() || it == '.' }) {
            val last = name.substringAfterLast('.')
            if (last.all { it.isDigit() } || last.startsWith("0x")) return v4(name)?.let(::isLocalV4) ?: true
        }
        if ('.' !in name) return true
        return LOCAL_NAMES.any { name == it || name.endsWith(".$it") }
    }

    /** The four bytes of an IPv4 address as one number, in any of the spellings a resolver takes: 127.0.0.1, 127.1, 0x7f.1, 017700000001, 2130706433. */
    private fun v4(text: String): Long? {
        val parts = text.split('.')
        if (parts.size > 4 || parts.any { it.isEmpty() }) return null
        val numbers = parts.map { part ->
            when {
                part.startsWith("0x") -> part.drop(2).toLongOrNull(16)
                part.length > 1 && part.startsWith("0") -> part.toLongOrNull(8)
                else -> part.toLongOrNull()
            } ?: return null
        }
        var value = 0L
        for ((index, number) in numbers.withIndex()) {
            val lastPart = index == numbers.lastIndex
            val room = if (lastPart) 1L shl (8 * (4 - index)) else 256L
            if (number < 0 || number >= room) return null
            value = if (lastPart) (value shl (8 * (4 - index))) or number else (value shl 8) or number
        }
        return value
    }

    private fun isLocalV4(address: Long): Boolean {
        val a = (address shr 24).toInt()
        val b = (address shr 16).toInt() and 0xFF
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 100 && b in 64..127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a >= 224 -> true
            else -> false
        }
    }

    private fun isLocalV6(text: String): Boolean {
        val address = text.substringBefore('%')
        if (address.startsWith("::")) return true
        val tail = address.substringAfterLast(':')
        if ('.' in tail) return v4(tail)?.let(::isLocalV4) ?: true
        val first = address.substringBefore(':').toIntOrNull(16) ?: return true
        if (first and 0xFE00 == 0xFC00 || first and 0xFFC0 == 0xFE80 || first and 0xFF00 == 0xFF00) return true
        if (first == 0x64 && address.startsWith("64:ff9b:")) {
            val groups = address.split(':').filter { it.isNotEmpty() }
            val high = groups.getOrNull(groups.size - 2)?.toLongOrNull(16) ?: return true
            val low = groups.last().toLongOrNull(16) ?: return true
            return isLocalV4((high shl 16) or low)
        }
        return first == 0
    }

    private val LOCAL_NAMES = listOf("localhost", "local", "lan", "internal", "intranet", "home", "corp", "home.arpa", "localdomain")

    fun segments(url: String): List<String> {
        val path = try {
            URI(url).path
        } catch (_: URISyntaxException) {
            null
        } ?: return emptyList()
        return path.split('/').filter { it.isNotEmpty() }
    }

    fun encodeSegment(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

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
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}
