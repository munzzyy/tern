package io.github.munzzyy.jackdaw.core.engine

class PatternException(val pattern: String, cause: Throwable) : Exception("Not a valid pattern: $pattern", cause)

/** User-written regular expressions, only ever run on short text so they cannot stall a check. */
class SafePattern private constructor(private val regex: Regex) {
    fun matches(text: String?): Boolean = regex.containsMatchIn(clip(text))

    /** First capture group when the pattern has one, else the whole match. */
    fun extract(text: String?): String? {
        val match = regex.find(clip(text)) ?: return null
        val group = if (match.groupValues.size > 1) match.groupValues[1] else match.value
        return group.takeIf { it.isNotEmpty() }
    }

    private fun clip(text: String?): String = text.orEmpty().take(MAX_INPUT)

    companion object {
        const val MAX_INPUT = 4000
        const val MAX_PATTERN = 500

        fun compile(pattern: String): SafePattern {
            try {
                require(pattern.length <= MAX_PATTERN) { "Pattern is longer than $MAX_PATTERN characters" }
                return SafePattern(Regex(pattern, RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                throw PatternException(pattern, e)
            }
        }

        fun compileOrNull(pattern: String?): SafePattern? = pattern?.takeIf { it.isNotBlank() }?.let(::compile)
    }
}
