package io.github.munzzyy.tern.core.source.web

internal object VersionGuess {
    private val NUMBER = Regex("\\d+(\\.\\d+){1,4}")
    private val STAGE_WORDS =
        setOf("snapshot", "nightly", "canary", "dev", "alpha", "beta", "preview", "pre", "rc")
    private const val MAX_INPUT = 4000
    private const val STAGE_WINDOW = 40

    fun find(text: String): String? {
        val capped = text.take(MAX_INPUT)
        val best = NUMBER.findAll(capped).maxByOrNull { it.value.length } ?: return null
        val stage = stageAfter(capped, best.range.last + 1) ?: return best.value
        return best.value + "-" + stage
    }

    /**
     * The first stage word in the name that goes on after the number, with its own trailing
     * number. The name goes on for as long as letters, digits, dots, dashes and underscores
     * follow, and is read for [STAGE_WINDOW] characters at most. A word in it that is no stage
     * word is passed over, so `22.0-Piers_beta1` gives `beta1`. Any other character, such as a
     * space or the start of a tag, ends the name and the search.
     */
    private fun stageAfter(s: String, from: Int): String? {
        val windowEnd = minOf(s.length, from + STAGE_WINDOW)
        var i = from
        while (i < windowEnd) {
            val c = s[i]
            if (c.isLetter()) {
                val wordEnd = letterRunEnd(s, i)
                val word = s.substring(i, wordEnd).lowercase()
                if (word in STAGE_WORDS) {
                    var numEnd = wordEnd
                    while (numEnd < s.length && s[numEnd].isDigit()) numEnd++
                    return s.substring(i, numEnd).lowercase()
                }
                i = wordEnd
            } else if (c.isDigit() || c == '.' || c == '-' || c == '_') {
                i++
            } else {
                return null
            }
        }
        return null
    }

    private fun letterRunEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isLetter()) i++
        return i
    }
}
