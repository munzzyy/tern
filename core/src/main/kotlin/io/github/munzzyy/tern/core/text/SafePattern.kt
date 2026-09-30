package io.github.munzzyy.tern.core.text

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class PatternException(val pattern: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A regular expression written by a user or carried in an import, which makes it hostile input.
 * Two limits apply. Matching is counted in steps where the engine reads the text through us (the
 * JVM does). Where it does not (Android hands the text to native code), a deadline applies instead:
 * the match runs on a thread of its own, and when the deadline passes the caller gets an error and
 * the thread is left behind to finish or die with the process.
 */
class SafePattern private constructor(
    private val regex: Regex,
    val pattern: String,
    private val maxSteps: Int,
    private val maxInput: Int,
) {
    fun matches(text: String?): Boolean = watched(pattern) { regex.containsMatchIn(metered(text)) }

    /**
     * The first capture group of the last match when the pattern has one, else the whole of that
     * match. The last match, because Obtainium takes that one, so a version pattern means the same
     * in both.
     */
    fun extract(text: String?): String? = watched(pattern) {
        val match = lastMatch(text) ?: return@watched null
        val group = if (match.groupValues.size > 1) match.groupValues[1] else match.value
        group.takeIf { it.isNotEmpty() }
    }

    /** The last match written out through [template]; null when nothing matches or the template gives nothing. */
    fun extract(text: String?, template: MatchTemplate): String? = groups(text)?.let(template::fill)

    /** The last match: the whole of it, then each group, null for a group that took no part. Null when nothing matches. */
    fun groups(text: String?): List<String?>? = watched(pattern) {
        val match = lastMatch(text) ?: return@watched null
        List(match.groups.size) { match.groups[it]?.value }
    }

    private fun lastMatch(text: String?): MatchResult? = regex.findAll(metered(text)).lastOrNull()

    private fun metered(text: String?): CharSequence = Metered(text.orEmpty().take(maxInput), Meter(maxSteps), pattern)

    private class OutOfSteps(val pattern: String) : RuntimeException()

    private class Meter(var left: Int)

    private class Metered(private val text: String, private val meter: Meter, private val pattern: String) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (--meter.left < 0) throw OutOfSteps(pattern)
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Metered(text.substring(startIndex, endIndex), meter, pattern)

        override fun toString(): String = text
    }

    companion object {
        const val MAX_INPUT = 4000
        const val MAX_PATTERN = 500
        const val MAX_STEPS = 2_000_000

        /** How much of a whole web page a pattern made by [compileForPages] reads. */
        const val MAX_PAGE = 1_000_000
        const val MAX_PAGE_STEPS = 20_000_000
        const val SINGLE_TIMEOUT_MS = 500L
        const val BATCH_TIMEOUT_MS = 2_000L

        private val inside = ThreadLocal<Boolean>()
        private val started = AtomicInteger()

        fun compile(pattern: String): SafePattern = compile(pattern, MAX_STEPS)

        /** For text as long as a whole web page: it reads up to [MAX_PAGE] characters, with the steps that takes. */
        fun compileForPages(pattern: String): SafePattern = compile(pattern, MAX_PAGE_STEPS, MAX_PAGE)

        internal fun compile(pattern: String, maxSteps: Int, maxInput: Int = MAX_INPUT): SafePattern {
            if (pattern.length > MAX_PATTERN) throw PatternException(pattern.take(40), "A pattern may be at most $MAX_PATTERN characters long")
            try {
                return SafePattern(Regex(pattern, RegexOption.IGNORE_CASE), pattern, maxSteps, maxInput)
            } catch (e: IllegalArgumentException) {
                throw PatternException(pattern, "Not a valid pattern: $pattern", e)
            }
        }

        fun compileOrNull(pattern: String?): SafePattern? = pattern?.takeIf { it.isNotBlank() }?.let(::compile)

        /**
         * Runs [body], which may match many times, under one deadline. Matches made inside it do
         * not start deadlines of their own. [what] names the work in the error.
         */
        fun <T> watched(what: String, timeoutMs: Long = BATCH_TIMEOUT_MS, body: () -> T): T {
            if (inside.get() == true) return translate(what, body)
            val task = FutureTask(Callable {
                inside.set(true)
                translate(what, body)
            })
            val thread = Thread(task, "pattern-${started.incrementAndGet()}")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            thread.start()
            try {
                return task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                thread.interrupt()
                throw PatternException(what, "This pattern takes too long to match: $what", e)
            } catch (e: InterruptedException) {
                thread.interrupt()
                Thread.currentThread().interrupt()
                throw PatternException(what, "Matching was interrupted: $what", e)
            } catch (e: ExecutionException) {
                when (val cause = e.cause) {
                    is RuntimeException -> throw cause
                    is PatternException -> throw cause
                    else -> throw PatternException(what, "This pattern could not be applied: $what", cause)
                }
            }
        }

        private fun <T> watched(pattern: String, body: () -> T): T = watched(pattern, SINGLE_TIMEOUT_MS, body)

        private fun <T> translate(what: String, body: () -> T): T = try {
            body()
        } catch (e: OutOfSteps) {
            throw PatternException(e.pattern, "This pattern takes too long to match: ${e.pattern}", e)
        } catch (e: StackOverflowError) {
            throw PatternException(what, "This pattern nests too deeply to match: $what", e)
        }
    }
}
