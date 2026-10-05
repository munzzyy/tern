package io.github.munzzyy.tern.core.notes

import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.text.Shown

sealed interface Span {
    data class Text(val text: String) : Span
    data class Bold(val spans: List<Span>) : Span
    data class Italic(val spans: List<Span>) : Span
    data class CodeSpan(val text: String) : Span
    data class Link(val spans: List<Span>, val url: String) : Span
}

sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Paragraph(val spans: List<Span>) : Block
    data class ListItem(val depth: Int, val ordered: Boolean, val number: Int?, val spans: List<Span>) : Block
    data class Code(val text: String) : Block
    data class Quote(val spans: List<Span>) : Block
    data object Rule : Block
}

private const val MAX_INPUT = 200_000
private const val MAX_BLOCKS = 2000
private const val MAX_DEPTH = 8
private const val INLINE_WINDOW = 500
private const val TAG_WINDOW = 2000

object ReleaseNotes {
    /** Parsed from what [Shown.prose] leaves of [text], so no block or link holds a character that is not drawn. */
    fun parse(text: String, format: NotesFormat): List<Block> {
        val capped = Shown.prose(text.take(MAX_INPUT), MAX_INPUT)
        return try {
            when (format) {
                NotesFormat.MARKDOWN -> MarkdownParser(capped).parse()
                NotesFormat.HTML -> HtmlParser(capped).parse()
                NotesFormat.PLAIN -> plainBlocks(capped)
            }
        } catch (_: Throwable) {
            // A parser bug must degrade to plain text, never crash the caller.
            listOf(Block.Paragraph(listOf(Span.Text(capped))))
        }
    }

    private fun plainBlocks(text: String): List<Block> =
        text.split(Regex("\\n{2,}")).asSequence().take(MAX_BLOCKS).map { it.trim() }.filter { it.isNotEmpty() }
            .map { Block.Paragraph(listOf(Span.Text(it))) }.toList()
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun spanText(span: Span): String = when (span) {
    is Span.Text -> span.text
    is Span.Bold -> span.spans.joinToString("") { spanText(it) }
    is Span.Italic -> span.spans.joinToString("") { spanText(it) }
    is Span.CodeSpan -> span.text
    is Span.Link -> span.spans.joinToString("") { spanText(it) }
}

private class MarkdownParser(private val text: String) {
    private val blocks = ArrayList<Block>()

    fun parse(): List<Block> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var i = 0
        while (i < lines.size && blocks.size < MAX_BLOCKS) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++
                isRule(trimmed) -> {
                    blocks.add(Block.Rule)
                    i++
                }
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = trimmed.take(3)
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        sb.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++
                    blocks.add(Block.Code(sb.toString().trimEnd('\n')))
                }
                headingMatch(trimmed) != null -> {
                    val (level, content) = headingMatch(trimmed)!!
                    blocks.add(Block.Heading(level, inline(content)))
                    i++
                }
                listMatch(line) != null -> {
                    val m = listMatch(line)!!
                    blocks.add(Block.ListItem(m.depth, m.ordered, m.number, inline(m.content)))
                    i++
                }
                trimmed.startsWith(">") -> {
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(lines[i].trim().removePrefix(">").trim())
                        i++
                    }
                    blocks.add(Block.Quote(inline(sb.toString())))
                }
                line.startsWith("    ") || line.startsWith("\t") -> {
                    val sb = StringBuilder()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t"))) {
                        val stripped = if (lines[i].startsWith("\t")) lines[i].substring(1) else lines[i].substring(4)
                        sb.append(stripped).append('\n')
                        i++
                    }
                    blocks.add(Block.Code(sb.toString().trimEnd('\n')))
                }
                else -> {
                    val sb = StringBuilder(line.trim())
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && headingMatch(lines[i].trim()) == null &&
                        listMatch(lines[i]) == null && !lines[i].trim().startsWith(">") && !isRule(lines[i].trim()) &&
                        !lines[i].trim().startsWith("```") && !lines[i].trim().startsWith("~~~") &&
                        !lines[i].startsWith("    ") && !lines[i].startsWith("\t")
                    ) {
                        sb.append(' ').append(lines[i].trim())
                        i++
                    }
                    blocks.add(Block.Paragraph(inline(sb.toString())))
                }
            }
        }
        return blocks
    }

    private fun isRule(t: String): Boolean {
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '_' && c != '*') return false
        return t.all { it == c || it == ' ' } && t.count { it == c } >= 3
    }

    private fun headingMatch(t: String): Pair<Int, String>? {
        var level = 0
        while (level < t.length && level < 6 && t[level] == '#') level++
        if (level == 0) return null
        if (level >= t.length || t[level] != ' ') return null
        return level to t.substring(level + 1).trim()
    }

    private data class ListMatch(val depth: Int, val ordered: Boolean, val number: Int?, val content: String)

    private fun listMatch(line: String): ListMatch? {
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        if (indent >= line.length) return null
        val rest = line.substring(indent)
        BULLET.find(rest)?.let { return ListMatch(minOf(indent / 2, 7), false, null, it.groupValues[1]) }
        ORDERED.find(rest)?.let {
            return ListMatch(minOf(indent / 2, 7), true, it.groupValues[1].toIntOrNull(), it.groupValues[2])
        }
        return null
    }

    private fun inline(text: String): List<Span> = InlineParser(text).parse(0)

    companion object {
        private val BULLET = Regex("^[-*+]\\s+(.*)$")
        private val ORDERED = Regex("^(\\d{1,9})[.)]\\s+(.*)$")
    }
}

private class InlineParser(private val text: String) {
    private var pos = 0

    fun parse(depth: Int): List<Span> {
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                spans.add(Span.Text(buf.toString()))
                buf.clear()
            }
        }
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '<' -> {
                    val close = boundedIndexOf('>', pos + 1)
                    val inside = if (close == -1) "" else text.substring(pos + 1, close)
                    when {
                        close != -1 && isUrlStart(pos + 1) && inside.none { it.isWhitespace() || it == '<' } -> {
                            flush()
                            spans.add(Span.Link(listOf(Span.Text(inside)), inside))
                            pos = close + 1
                        }
                        close != -1 && startsTag(pos + 1) -> pos = close + 1
                        else -> {
                            buf.append(c)
                            pos++
                        }
                    }
                }
                c == '`' -> {
                    val close = boundedIndexOf('`', pos + 1)
                    if (close != -1) {
                        flush()
                        spans.add(Span.CodeSpan(text.substring(pos + 1, close)))
                        pos = close + 1
                    } else {
                        buf.append(c)
                        pos++
                    }
                }
                c == '!' && pos + 1 < text.length && text[pos + 1] == '[' -> {
                    val parsed = tryLink(pos + 1, depth)
                    if (parsed != null) {
                        flush()
                        spans.add(Span.Text(parsed.label))
                        pos = parsed.end
                    } else {
                        buf.append(c)
                        pos++
                    }
                }
                c == '[' -> {
                    val parsed = tryLink(pos, depth)
                    if (parsed != null) {
                        flush()
                        if (parsed.url != null && isHttpUrl(parsed.url)) spans.add(Span.Link(parsed.spans, parsed.url))
                        else spans.addAll(parsed.spans)
                        pos = parsed.end
                    } else {
                        buf.append(c)
                        pos++
                    }
                }
                c == '_' && text.getOrNull(pos - 1)?.isLetterOrDigit() == true -> {
                    while (pos < text.length && text[pos] == '_') {
                        buf.append('_')
                        pos++
                    }
                }
                (c == '*' || c == '_') && pos + 1 < text.length && text[pos + 1] == c -> {
                    val close = closing("" + c + c, pos + 2)
                    if (close != -1 && depth < MAX_DEPTH) {
                        flush()
                        spans.add(Span.Bold(InlineParser(text.substring(pos + 2, close)).parse(depth + 1)))
                        pos = close + 2
                    } else {
                        buf.append(c)
                        pos++
                    }
                }
                c == '*' || c == '_' -> {
                    val close = closing(c.toString(), pos + 1)
                    if (close != -1 && close > pos + 1 && depth < MAX_DEPTH) {
                        flush()
                        spans.add(Span.Italic(InlineParser(text.substring(pos + 1, close)).parse(depth + 1)))
                        pos = close + 1
                    } else {
                        buf.append(c)
                        pos++
                    }
                }
                isUrlStart(pos) -> {
                    val end = urlEnd(pos)
                    flush()
                    val url = text.substring(pos, end)
                    spans.add(Span.Link(listOf(Span.Text(url)), url))
                    pos = end
                }
                else -> {
                    buf.append(c)
                    pos++
                }
            }
        }
        flush()
        return spans
    }

    private data class LinkParse(val end: Int, val url: String?, val spans: List<Span>, val label: String)

    private fun tryLink(start: Int, depth: Int): LinkParse? {
        val closeBracket = boundedIndexOf(']', start + 1)
        if (closeBracket == -1) return null
        val label = text.substring(start + 1, closeBracket)
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = boundedIndexOf(')', closeBracket + 2)
        if (closeParen == -1) return null
        val url = text.substring(closeBracket + 2, closeParen).trim()
        val inner = if (depth < MAX_DEPTH) InlineParser(label).parse(depth + 1) else listOf(Span.Text(label))
        return LinkParse(closeParen + 1, url, inner, label)
    }

    private fun isUrlStart(at: Int): Boolean =
        text.regionMatches(at, "https://", 0, 8, ignoreCase = true) || text.regionMatches(at, "http://", 0, 7, ignoreCase = true)

    /** Where a bare address ends: a full stop, comma or other mark that ends it belongs to the sentence. */
    private fun urlEnd(at: Int): Int {
        val limit = minOf(text.length, at + INLINE_WINDOW * 4)
        var i = at
        while (i < limit && !text[i].isWhitespace() && text[i] !in "<>\"'()[]") i++
        while (i > at && text[i - 1] in ".,;:!?") i--
        return i
    }

    /** A raw tag opens with a letter, "/" or "!", so "a < b" is text. */
    private fun startsTag(at: Int): Boolean = text.getOrNull(at)?.let { it.isLetter() || it == '/' || it == '!' } == true

    /**
     * Where [marker] closes. An underscore closes only where no letter or digit follows, so
     * the underscores inside a name such as app_arm64_v8a.apk are left as they are.
     */
    private fun closing(marker: String, from: Int): Int {
        val end = minOf(text.length, from + INLINE_WINDOW)
        var i = from
        while (i + marker.length <= end) {
            val closes = text.regionMatches(i, marker, 0, marker.length) &&
                (marker[0] != '_' || text.getOrNull(i + marker.length)?.isLetterOrDigit() != true)
            if (closes) return i
            i++
        }
        return -1
    }

    private fun boundedIndexOf(target: Char, from: Int): Int {
        val end = minOf(text.length, from + INLINE_WINDOW)
        var i = from
        while (i < end) {
            if (text[i] == target) return i
            i++
        }
        return -1
    }
}

private class HtmlParser(private val text: String) {
    private class Frame(val tag: String, val children: ArrayList<Span>, val href: String?)

    private val blocks = ArrayList<Block>()
    private val spanStack = ArrayList<Frame>()
    private var currentSpans = ArrayList<Span>()
    private var blockKind: String? = null
    private val listOrdered = ArrayList<Boolean>()
    private val listCounter = ArrayList<Int>()
    private var pos = 0

    fun parse(): List<Block> {
        while (pos < text.length) {
            val lt = text.indexOf('<', pos)
            if (lt == -1) {
                appendText(text.substring(pos))
                break
            }
            if (lt > pos) appendText(text.substring(pos, lt))
            val gt = boundedIndexOf('>', lt + 1)
            if (gt == -1) {
                appendText("<")
                pos = lt + 1
                continue
            }
            pos = gt + 1
            handleTag(text.substring(lt + 1, gt))
        }
        finalizeBlock()
        return blocks
    }

    private fun boundedIndexOf(target: Char, from: Int): Int {
        val end = minOf(text.length, from + TAG_WINDOW)
        var i = from
        while (i < end) {
            if (text[i] == target) return i
            i++
        }
        return -1
    }

    private fun handleTag(raw: String) {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("?")) return
        val closing = s.startsWith("/")
        if (closing) s = s.substring(1)
        val nameEnd = s.indexOfFirst { it.isWhitespace() || it == '/' }.let { if (it == -1) s.length else it }
        val name = s.substring(0, nameEnd).lowercase()
        if (name.isEmpty()) return
        val attrs = s.substring(nameEnd)

        when (name) {
            "br" -> appendText(" ")
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "pre", "blockquote" -> {
                finalizeBlock()
                if (!closing) blockKind = name
            }
            "ul" -> if (!closing) listOrdered.add(false) else popList()
            "ol" -> if (!closing) {
                listOrdered.add(true)
                listCounter.add(0)
            } else popList()
            "b", "strong" -> pushOrPop(closing, "b")
            "i", "em" -> pushOrPop(closing, "i")
            "code" -> pushOrPop(closing, "code")
            "a" -> if (!closing) spanStack.add(Frame("a", ArrayList(), extractHref(attrs))) else popInline("a")
            else -> Unit
        }
    }

    private fun popList() {
        if (listOrdered.isNotEmpty()) {
            val wasOrdered = listOrdered.removeAt(listOrdered.lastIndex)
            if (wasOrdered && listCounter.isNotEmpty()) listCounter.removeAt(listCounter.lastIndex)
        }
    }

    private fun pushOrPop(closing: Boolean, tag: String) {
        if (!closing) spanStack.add(Frame(tag, ArrayList(), null)) else popInline(tag)
    }

    private fun popInline(tag: String) {
        val idx = spanStack.indexOfLast { it.tag == tag }
        if (idx == -1) return
        while (spanStack.size > idx) {
            val frame = spanStack.removeAt(spanStack.lastIndex)
            val wrapped: Span = when (frame.tag) {
                "b" -> Span.Bold(frame.children)
                "i" -> Span.Italic(frame.children)
                "code" -> Span.CodeSpan(frame.children.joinToString("") { spanText(it) })
                "a" -> {
                    val href = frame.href
                    if (href != null && isHttpUrl(href)) Span.Link(frame.children, href)
                    else Span.Text(frame.children.joinToString("") { spanText(it) })
                }
                else -> Span.Text(frame.children.joinToString("") { spanText(it) })
            }
            emit(wrapped)
        }
    }

    private fun emit(span: Span) {
        if (spanStack.isNotEmpty()) spanStack.last().children.add(span) else currentSpans.add(span)
    }

    private fun appendText(s: String) {
        if (s.isEmpty()) return
        emit(Span.Text(s))
    }

    private fun extractHref(attrs: String): String? {
        val match = HREF.find(attrs) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun finalizeBlock() {
        while (spanStack.isNotEmpty()) popInline(spanStack.last().tag)
        val kind = blockKind
        blockKind = null
        val spans = currentSpans
        currentSpans = ArrayList()
        if (spans.isEmpty() && kind == null) return
        if (blocks.size >= MAX_BLOCKS) return
        val block: Block = when (kind) {
            "h1" -> Block.Heading(1, spans)
            "h2" -> Block.Heading(2, spans)
            "h3" -> Block.Heading(3, spans)
            "h4" -> Block.Heading(4, spans)
            "h5" -> Block.Heading(5, spans)
            "h6" -> Block.Heading(6, spans)
            "pre" -> Block.Code(spans.joinToString("") { spanText(it) })
            "blockquote" -> Block.Quote(spans)
            "li" -> {
                val ordered = listOrdered.lastOrNull() ?: false
                val number = if (ordered && listCounter.isNotEmpty()) {
                    listCounter[listCounter.lastIndex] += 1
                    listCounter[listCounter.lastIndex]
                } else null
                Block.ListItem(minOf(maxOf(listOrdered.size - 1, 0), 7), ordered, number, spans)
            }
            else -> if (spans.isEmpty()) return else Block.Paragraph(spans)
        }
        blocks.add(block)
    }

    companion object {
        private val HREF = Regex("(?i)href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))")
    }
}
