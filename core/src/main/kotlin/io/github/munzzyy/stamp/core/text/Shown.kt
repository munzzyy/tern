package io.github.munzzyy.stamp.core.text

/**
 * Text that came from a server, a file or another app, made fit to be shown. A name can carry a
 * mark that turns the direction of writing, or a character of no width, and then look like
 * another name. Text the user typed does not come through here.
 */
object Shown {
    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val NEXT_LINE = 0x85

    /**
     * [text] as one line of at most [max] characters. Control characters, format characters,
     * private use, unassigned code points and halves of a surrogate pair are left out. Every run
     * of white space, line breaks and separators included, becomes one space, and there is none
     * at either end. The cut never divides a surrogate pair.
     */
    fun line(text: String, max: Int): String {
        val out = StringBuilder(minOf(text.length, maxOf(max, 0)))
        var spaceOwed = false
        var i = 0
        while (i < text.length) {
            val point = text.codePointAt(i)
            i += Character.charCount(point)
            if (isSpace(point)) {
                spaceOwed = out.isNotEmpty()
                continue
            }
            if (!isDrawn(point)) continue
            val needed = Character.charCount(point) + if (spaceOwed) 1 else 0
            if (out.length + needed > max) break
            if (spaceOwed) out.append(' ')
            spaceOwed = false
            out.appendCodePoint(point)
        }
        return out.toString()
    }

    /** [line], or null when [text] is null or nothing of it is left. */
    fun lineOrNull(text: String?, max: Int): String? = text?.let { line(it, max) }?.takeIf { it.isNotEmpty() }

    /**
     * [text] of several lines, at most [max] characters. The same characters are left out as in
     * [line], with these differences: line breaks and tabs stay, a line or paragraph separator
     * becomes a line break, and the two joiners of no width stay, because Persian and the scripts
     * of India are written with them and they cannot turn the direction of writing.
     */
    fun prose(text: String, max: Int): String {
        val out = StringBuilder(minOf(text.length, maxOf(max, 0)))
        var i = 0
        while (i < text.length) {
            val point = text.codePointAt(i)
            i += Character.charCount(point)
            val kept = when {
                point == '\n'.code || point == '\r'.code || point == '\t'.code -> point
                isSeparator(point) -> '\n'.code
                point == ZERO_WIDTH_NON_JOINER || point == ZERO_WIDTH_JOINER -> point
                isDrawn(point) -> point
                else -> continue
            }
            if (out.length + Character.charCount(kept) > max) break
            out.appendCodePoint(kept)
        }
        return out.toString()
    }

    private fun isSpace(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point) || point == NEXT_LINE

    private fun isSeparator(point: Int): Boolean = point == NEXT_LINE || when (Character.getType(point).toByte()) {
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    private fun isDrawn(point: Int): Boolean = when (Character.getType(point).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        -> false
        else -> true
    }
}
