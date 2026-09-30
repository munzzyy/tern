package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.xml.XmlScanner

internal data class AnchorLink(val href: String, val text: String)

/**
 * Tolerant scanner for `<a href=...>text</a>`, and for addresses outside such tags, in real-world,
 * not-necessarily-well-formed HTML. Bounded windows keep it linear even on crafted input with no
 * closing tags.
 */
internal object LinkScanner {
    private const val TAG_WINDOW = 4000
    private const val TEXT_WINDOW = 4000
    private const val MAX_LINKS = 20_000

    fun baseHref(html: String): String? {
        val lt = html.indexOf("<base", 0, ignoreCase = true)
        if (lt == -1) return null
        val gt = boundedIndexOf(html, '>', lt + 5, TAG_WINDOW)
        if (gt == -1) return null
        val href = attribute(html.substring(lt + 5, gt), "href") ?: return null
        return XmlScanner.decode(href)
    }

    fun anchors(html: String): List<AnchorLink> {
        val out = ArrayList<AnchorLink>()
        var pos = 0
        while (pos < html.length && out.size < MAX_LINKS) {
            val lt = html.indexOf("<a", pos, ignoreCase = true)
            if (lt == -1) break
            val after = lt + 2
            if (after < html.length && !html[after].isWhitespace() && html[after] != '>' && html[after] != '/') {
                pos = lt + 2
                continue
            }
            val gt = boundedIndexOf(html, '>', after, TAG_WINDOW)
            if (gt == -1) {
                pos = lt + 2
                continue
            }
            val attrs = html.substring(after, gt)
            val href = attribute(attrs, "href")
            val closeIdx = boundedIndexOf(html, "</a", gt + 1, TEXT_WINDOW)
            val textEnd = if (closeIdx == -1) minOf(html.length, gt + 1 + TEXT_WINDOW) else closeIdx
            val text = XmlScanner.decode(stripTags(html.substring(gt + 1, textEnd))).trim()
            if (href != null) out.add(AnchorLink(XmlScanner.decode(href), text))
            pos = if (closeIdx == -1) gt + 1 else closeIdx + 3
        }
        return out
    }

    private fun stripTags(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '<') {
                val close = boundedIndexOf(s, '>', i + 1, 500)
                if (close != -1) {
                    i = close + 1
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    /**
     * Addresses outside `<a>` tags, in the order found: string values when the page is JSON, bare
     * http(s) addresses in its text, and attribute values of any tag that are absolute or start
     * with a slash. None has link text.
     */
    fun addresses(html: String): List<AnchorLink> {
        val out = LinkedHashSet<String>()
        for (value in jsonStrings(html)) {
            if (out.size >= MAX_LINKS) break
            BARE_ADDRESS.findAll(value).forEach { out.add(trimPunctuation(it.value)) }
            if (value.startsWith("/")) out.add(value.trim())
        }
        for (match in BARE_ADDRESS.findAll(html)) {
            if (out.size >= MAX_LINKS) break
            out.add(trimPunctuation(XmlScanner.decode(match.value)))
        }
        attributeAddresses(html, out)
        return out.filter { it.isNotEmpty() }.take(MAX_LINKS).map { AnchorLink(it, "") }
    }

    private val BARE_ADDRESS = Regex("""https?://[^\s"'<>()\[\]{}\\]+""", RegexOption.IGNORE_CASE)

    /** A full stop or comma after an address in running text belongs to the sentence. */
    private fun trimPunctuation(address: String): String = address.trimEnd('.', ',', ';', ':', '!', '?')

    private fun jsonStrings(text: String): List<String> {
        val start = text.trimStart()
        if (!start.startsWith("{") && !start.startsWith("[")) return emptyList()
        val root = try {
            Json.parse(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<String>()
        fun collect(value: JsonValue) {
            when (value) {
                is JsonString -> out.add(value.value)
                is JsonArray -> value.items.forEach(::collect)
                is JsonObject -> value.fields.values.forEach(::collect)
                else -> Unit
            }
        }
        collect(root)
        return out
    }

    /** Every search here only moves forward, the one for the next `>` included, so crafted input costs one pass. */
    private fun attributeAddresses(html: String, out: MutableSet<String>) {
        var pos = 0
        var gt = html.indexOf('>')
        while (pos < html.length && out.size < MAX_LINKS) {
            val lt = html.indexOf('<', pos)
            if (lt == -1) break
            if (gt != -1 && gt <= lt) gt = html.indexOf('>', lt + 1)
            if (gt == -1) break
            if (gt - lt > TAG_WINDOW || !html[lt + 1].isLetter()) {
                pos = lt + 1
                continue
            }
            var nameEnd = lt + 1
            while (nameEnd < gt && !html[nameEnd].isWhitespace() && html[nameEnd] != '/') nameEnd++
            for ((_, value) in attributes(html.substring(nameEnd, gt))) {
                val decoded = XmlScanner.decode(value).trim()
                if (decoded.startsWith("/") || decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) {
                    out.add(decoded)
                }
            }
            pos = gt + 1
        }
    }

    private fun attribute(attrs: String, name: String): String? = attributes(attrs).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Every attribute that has a value, in order, names as written. */
    private fun attributes(attrs: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < attrs.length) {
            while (i < attrs.length && (attrs[i].isWhitespace() || attrs[i] == '/')) i++
            if (i >= attrs.length) break
            val nameStart = i
            while (i < attrs.length && attrs[i] != '=' && !attrs[i].isWhitespace()) i++
            val attrName = attrs.substring(nameStart, i)
            while (i < attrs.length && attrs[i].isWhitespace()) i++
            var value: String? = null
            if (i < attrs.length && attrs[i] == '=') {
                i++
                while (i < attrs.length && attrs[i].isWhitespace()) i++
                if (i < attrs.length && (attrs[i] == '"' || attrs[i] == '\'')) {
                    val quote = attrs[i]
                    i++
                    val end = attrs.indexOf(quote, i)
                    if (end == -1) {
                        value = attrs.substring(i)
                        i = attrs.length
                    } else {
                        value = attrs.substring(i, end)
                        i = end + 1
                    }
                } else {
                    val start = i
                    while (i < attrs.length && !attrs[i].isWhitespace()) i++
                    value = attrs.substring(start, i)
                }
            }
            if (value != null) out.add(attrName to value)
        }
        return out
    }

    fun title(html: String): String? {
        val open = html.indexOf("<title", 0, ignoreCase = true)
        if (open == -1) return null
        val start = boundedIndexOf(html, '>', open + 6, TAG_WINDOW)
        if (start == -1) return null
        val end = boundedIndexOf(html, "</title", start + 1, 500)
        if (end == -1) return null
        return XmlScanner.decode(html.substring(start + 1, end)).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun boundedIndexOf(s: String, target: String, from: Int, window: Int): Int {
        val end = minOf(s.length, from + window)
        var i = from
        while (i <= end - target.length) {
            if (s.regionMatches(i, target, 0, target.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    private fun boundedIndexOf(s: String, target: Char, from: Int, window: Int): Int {
        val end = minOf(s.length, from + window)
        var i = from
        while (i < end) {
            if (s[i] == target) return i
            i++
        }
        return -1
    }
}
