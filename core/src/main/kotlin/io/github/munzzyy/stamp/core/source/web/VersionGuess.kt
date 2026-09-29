package io.github.munzzyy.stamp.core.source.web

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
     * Looks past a codename for a stage word this same release name carries, so
     * `22.0-Piers_beta1` is not cut down to `22.0`. Stops at the first whole word that is
     * not a stage word, so a processor name or file ending is never read as one. Returns the
     * stage word with its own trailing number, not the codename between it and the version.
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
            } else {
                i++
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
