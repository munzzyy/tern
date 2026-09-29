package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.xml.XmlScanner

internal data class AnchorLink(val href: String, val text: String)

/**
 * Tolerant scanner for `<a href=...>text</a>` in real-world, not-necessarily-well-formed HTML.
 * Bounded windows keep it linear even on crafted input with no closing tags.
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
            val closeIdx = html.indexOf("</a", gt + 1, ignoreCase = true)
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

    private fun attribute(attrs: String, name: String): String? {
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
            if (attrName.equals(name, ignoreCase = true) && value != null) return value
        }
        return null
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
