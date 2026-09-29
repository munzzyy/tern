package io.github.munzzyy.stamp.core.version

enum class Stage { DEV, ALPHA, BETA, RC, RELEASE }

/**
 * A version as developers actually write them: tags with prefixes, flavors, dates, git-describe
 * tails. Ordering is by numbers first, then stage, then stage number.
 */
class Version private constructor(
    val raw: String,
    val numbers: List<Long>,
    val stage: Stage,
    val stageNumber: Long,
) : Comparable<Version> {
    /** False when no number could be found; such versions only compare equal to themselves. */
    val isComparable: Boolean get() = numbers.isNotEmpty()

    val isPrerelease: Boolean get() = stage != Stage.RELEASE

    override fun compareTo(other: Version): Int {
        if (!isComparable || !other.isComparable) {
            return if (isComparable == other.isComparable) raw.compareTo(other.raw) else if (isComparable) 1 else -1
        }
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val a = numbers.getOrElse(i) { 0L }
            val b = other.numbers.getOrElse(i) { 0L }
            if (a != b) return a.compareTo(b)
        }
        if (stage != other.stage) return stage.compareTo(other.stage)
        return stageNumber.compareTo(other.stageNumber)
    }

    /** Same release once prefixes, flavors and build metadata are set aside. */
    fun sameAs(other: Version): Boolean =
        if (isComparable && other.isComparable) compareTo(other) == 0 else raw.trim().equals(other.raw.trim(), ignoreCase = true)

    override fun equals(other: Any?): Boolean = other is Version && raw == other.raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw

    companion object {
        private const val MAX_LENGTH = 256
        private const val MAX_NUMBERS = 8

        private val STAGES = listOf(
            "snapshot" to Stage.DEV, "nightly" to Stage.DEV, "canary" to Stage.DEV, "dev" to Stage.DEV,
            "alpha" to Stage.ALPHA, "beta" to Stage.BETA,
            "preview" to Stage.RC, "pre" to Stage.RC, "rc" to Stage.RC,
        )
        private val SHORT_STAGES = mapOf('a' to Stage.ALPHA, 'b' to Stage.BETA, 'c' to Stage.RC)

        fun parse(text: String): Version {
            val raw = text.trim()
            val s = raw.take(MAX_LENGTH).lowercase().substringBefore('+')
            val start = coreStart(s)
            if (start < 0) return Version(raw, emptyList(), stageIn(s, 0, s.length)?.first ?: Stage.RELEASE, 0)

            val numbers = ArrayList<Long>()
            var i = start
            while (i < s.length && numbers.size < MAX_NUMBERS) {
                val end = digitsEnd(s, i)
                if (end - i > 18) return Version(raw, emptyList(), Stage.RELEASE, 0)
                numbers.add(s.substring(i, end).toLong())
                i = end
                val next = i + 1
                val separated = i < s.length && (s[i] == '.' || s[i] == '_' || s[i] == '-')
                if (!separated || next >= s.length || !s[next].isDigit()) break
                if (s[i] == '-' && looksLikeGitHash(s, digitsEnd(s, next))) {
                    numbers.add(s.substring(next, digitsEnd(s, next)).toLong())
                    i = digitsEnd(s, next)
                    break
                }
                i = next
            }

            val before = stageIn(s, 0, start)
            val after = stageAfter(s, i)
            val stage = after?.first ?: before?.first ?: Stage.RELEASE
            return Version(raw, numbers, stage, after?.second ?: 0)
        }

        fun same(a: String, b: String): Boolean = parse(a).sameAs(parse(b))

        fun compare(a: String, b: String): Int = parse(a).compareTo(parse(b))

        /** First digit that begins a version: at the start, after a separator, or after a lone v. */
        private fun coreStart(s: String): Int {
            var i = 0
            while (i < s.length) {
                if (s[i].isDigit()) {
                    val prev = if (i == 0) ' ' else s[i - 1]
                    val afterV = prev == 'v' && (i < 2 || !s[i - 2].isLetterOrDigit())
                    if (!prev.isLetterOrDigit() || afterV) {
                        val end = digitsEnd(s, i)
                        val followedByLetters = end < s.length && s[end].isLetter() && !isStageAt(s, end)
                        if (!followedByLetters || end == s.length) return i
                        i = end
                        continue
                    }
                    i = digitsEnd(s, i)
                    continue
                }
                i++
            }
            return -1
        }

        private fun digitsEnd(s: String, from: Int): Int {
            var i = from
            while (i < s.length && s[i].isDigit()) i++
            return i
        }

        private fun looksLikeGitHash(s: String, from: Int): Boolean {
            if (from + 2 >= s.length || s[from] != '-' || s[from + 1] != 'g') return false
            var i = from + 2
            while (i < s.length && (s[i].isDigit() || s[i] in 'a'..'f')) i++
            return i - (from + 2) >= 6
        }

        private fun isStageAt(s: String, at: Int): Boolean =
            STAGES.any { s.startsWith(it.first, at) } ||
                (s[at] in SHORT_STAGES && (at + 1 == s.length || s[at + 1].isDigit()))

        private fun stageIn(s: String, from: Int, to: Int): Pair<Stage, Long>? {
            val part = s.substring(from, to)
            val hit = STAGES.firstOrNull { (word, _) -> wordIn(part, word) } ?: return null
            return hit.second to 0L
        }

        private fun wordIn(part: String, word: String): Boolean {
            var at = part.indexOf(word)
            while (at >= 0) {
                val before = at == 0 || !part[at - 1].isLetter()
                val end = at + word.length
                val after = end == part.length || !part[end].isLetter()
                if (before && after) return true
                at = part.indexOf(word, at + 1)
            }
            return false
        }

        private fun stageAfter(s: String, from: Int): Pair<Stage, Long>? {
            var i = from
            while (i < s.length && (s[i] == '-' || s[i] == '.' || s[i] == '_' || s[i] == ' ' || s[i] == '~')) i++
            if (i >= s.length) return null
            val word = STAGES.firstOrNull { s.startsWith(it.first, i) }
            val stage: Stage
            if (word != null) {
                stage = word.second
                i += word.first.length
            } else if (s[i] in SHORT_STAGES && (i + 1 == s.length || s[i + 1].isDigit())) {
                stage = SHORT_STAGES.getValue(s[i])
                i++
            } else {
                return stageIn(s, i, s.length)
            }
            while (i < s.length && (s[i] == '-' || s[i] == '.' || s[i] == '_')) i++
            val end = digitsEnd(s, i)
            val number = if (end > i && end - i <= 18) s.substring(i, end).toLong() else 0L
            return stage to number
        }
    }
}
