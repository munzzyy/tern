package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind

/** When the background check tries again what it could not check, as Obtainium does: a few times, later each time. */
internal object Retries {
    const val MOST = 4
    private const val FIRST_MS = 30_000L
    private const val LONGEST_RATE_WAIT_MS = 6 * 60 * 60 * 1000L

    /**
     * The apps of [problems] worth checking again after [attempt] tries, and the wait before: twice
     * as long each time for the network, until the reset for a rate limit. Other problems do not
     * pass by themselves, so they are left for the next scheduled check.
     */
    fun plan(problems: List<Pair<String, Problem>>, attempt: Int, now: Long): Pair<List<String>, Long>? {
        if (attempt >= MOST) return null
        var wait = FIRST_MS shl attempt
        val again = problems.filter { (_, problem) ->
            when (problem.kind) {
                ProblemKind.NETWORK -> true
                ProblemKind.RATE_LIMITED -> {
                    problem.retryAtMs?.let { wait = maxOf(wait, (it - now).coerceAtMost(LONGEST_RATE_WAIT_MS)) }
                    true
                }
                else -> false
            }
        }.map { it.first }
        return if (again.isEmpty()) null else again to wait
    }
}
