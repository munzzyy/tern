package io.github.munzzyy.tern.core.text

/**
 * Which part of a match becomes the text, written the way Obtainium's "match group to use" is:
 * `N` or `$N` names group N, 0 being the whole match, and a template such as `$1.$2` joins
 * several. `\$` writes a dollar sign.
 */
class MatchTemplate private constructor(private val parts: List<Part>) {
    private sealed interface Part

    private class Text(val text: String) : Part

    private class Group(val index: Int) : Part

    /** False for a template that names no group, which can never give any text. */
    val isValid: Boolean get() = parts.any { it is Group }

    /**
     * The text for a match whose [groups] are the whole match first and then each group, null for
     * one that took no part. Null when the template names a group the match does not have, and
     * when nothing is left.
     */
    fun fill(groups: List<String?>): String? {
        if (!isValid) return null
        val out = StringBuilder()
        for (part in parts) {
            when (part) {
                is Text -> out.append(part.text)
                is Group -> out.append(if (part.index < groups.size) groups[part.index].orEmpty() else return null)
            }
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Null for a blank template: the pattern's own rule then applies. */
        fun parse(template: String?): MatchTemplate? {
            val text = template?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.all { it in '0'..'9' }) return MatchTemplate(listOf(Group(index(text))))
            val parts = ArrayList<Part>()
            val literal = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length && text[i + 1] == '$' -> {
                        literal.append('$')
                        i += 2
                    }
                    c == '$' && i + 1 < text.length && text[i + 1] in '0'..'9' -> {
                        var end = i + 1
                        while (end < text.length && text[end] in '0'..'9') end++
                        if (literal.isNotEmpty()) {
                            parts.add(Text(literal.toString()))
                            literal.clear()
                        }
                        parts.add(Group(index(text.substring(i + 1, end))))
                        i = end
                    }
                    else -> {
                        literal.append(c)
                        i++
                    }
                }
            }
            if (literal.isNotEmpty()) parts.add(Text(literal.toString()))
            return MatchTemplate(parts)
        }

        /** A number too large to be a group names none. */
        private fun index(digits: String): Int = digits.toIntOrNull() ?: Int.MAX_VALUE
    }
}
