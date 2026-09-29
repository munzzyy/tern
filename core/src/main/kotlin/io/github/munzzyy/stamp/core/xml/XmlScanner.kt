package io.github.munzzyy.stamp.core.xml

class XmlException(message: String) : Exception(message)

data class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    val text: String,
) {
    /** Name without a namespace prefix. */
    val localName: String get() = name.substringAfter(':')

    fun child(local: String): XmlElement? = children.firstOrNull { it.localName == local }

    fun children(local: String): List<XmlElement> = children.filter { it.localName == local }

    fun childText(local: String): String? = child(local)?.text?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Reads feeds (Atom, RSS). Doctype declarations are skipped and never resolved, and only the five
 * predefined entities plus numeric references are expanded, so there is nothing to inject.
 */
object XmlScanner {
    private const val MAX_DEPTH = 64
    private const val MAX_ELEMENTS = 200_000

    fun parse(text: String): XmlElement = Parser(text).document()

    private class Parser(private val s: String) {
        private var pos = 0
        private var elements = 0

        fun document(): XmlElement {
            skipProlog()
            if (pos >= s.length || s[pos] != '<') throw XmlException("No root element")
            val root = element(0)
            skipProlog()
            if (pos < s.length) throw XmlException("Content after the root element")
            return root
        }

        private fun skipProlog() {
            while (pos < s.length) {
                when {
                    s[pos].isWhitespace() || s[pos] == '﻿' -> pos++
                    s.startsWith("<?", pos) -> skipPast("?>")
                    s.startsWith("<!--", pos) -> skipPast("-->")
                    s.startsWith("<!DOCTYPE", pos, ignoreCase = true) -> skipDoctype()
                    else -> return
                }
            }
        }

        private fun skipPast(end: String) {
            val at = s.indexOf(end, pos)
            if (at < 0) throw XmlException("Unterminated markup")
            pos = at + end.length
        }

        private fun skipDoctype() {
            var depth = 0
            while (pos < s.length) {
                when (s[pos]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) {
                        pos++
                        return
                    }
                }
                pos++
            }
            throw XmlException("Unterminated doctype")
        }

        private fun element(depth: Int): XmlElement {
            if (depth > MAX_DEPTH) throw XmlException("Nesting deeper than $MAX_DEPTH")
            if (++elements > MAX_ELEMENTS) throw XmlException("More than $MAX_ELEMENTS elements")
            pos++
            val name = name()
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                skipWhitespace()
                if (pos >= s.length) throw XmlException("Unterminated tag")
                if (s[pos] == '/') {
                    if (!s.startsWith("/>", pos)) throw XmlException("Malformed tag")
                    pos += 2
                    return XmlElement(name, attributes, emptyList(), "")
                }
                if (s[pos] == '>') {
                    pos++
                    break
                }
                val key = name()
                skipWhitespace()
                if (pos >= s.length || s[pos] != '=') throw XmlException("Attribute without a value")
                pos++
                skipWhitespace()
                if (pos >= s.length || (s[pos] != '"' && s[pos] != '\'')) throw XmlException("Unquoted attribute")
                val quote = s[pos++]
                val end = s.indexOf(quote, pos)
                if (end < 0) throw XmlException("Unterminated attribute")
                attributes[key] = decode(s.substring(pos, end))
                pos = end + 1
            }

            val children = ArrayList<XmlElement>()
            val text = StringBuilder()
            while (true) {
                if (pos >= s.length) throw XmlException("Unterminated element $name")
                val lt = s.indexOf('<', pos)
                if (lt < 0) throw XmlException("Unterminated element $name")
                if (lt > pos) text.append(decode(s.substring(pos, lt)))
                pos = lt
                when {
                    s.startsWith("</", pos) -> {
                        pos += 2
                        val closing = name()
                        skipWhitespace()
                        if (pos >= s.length || s[pos] != '>') throw XmlException("Malformed closing tag")
                        pos++
                        if (closing != name) throw XmlException("Expected </$name> but found </$closing>")
                        return XmlElement(name, attributes, children, text.toString())
                    }
                    s.startsWith("<!--", pos) -> skipPast("-->")
                    s.startsWith("<![CDATA[", pos) -> {
                        val end = s.indexOf("]]>", pos)
                        if (end < 0) throw XmlException("Unterminated CDATA")
                        text.append(s, pos + 9, end)
                        pos = end + 3
                    }
                    s.startsWith("<?", pos) -> skipPast("?>")
                    s.startsWith("<!", pos) -> throw XmlException("Declaration inside content")
                    else -> children.add(element(depth + 1))
                }
            }
        }

        private fun name(): String {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == ':' || s[pos] == '_' || s[pos] == '-' || s[pos] == '.')) pos++
            if (pos == start) throw XmlException("Expected a name")
            return s.substring(start, pos)
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }

    fun decode(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, end)
            val replacement = when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> numeric(entity)
            }
            if (replacement == null) {
                out.append(c)
                i++
            } else {
                out.append(replacement)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun numeric(entity: String): String? {
        if (!entity.startsWith("#")) return null
        val code = if (entity.startsWith("#x") || entity.startsWith("#X")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
        if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
        return String(Character.toChars(code))
    }
}
