package io.github.munzzyy.tern.ui.importing

import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SearchHit
import kotlinx.coroutines.CancellationException

enum class SkipReason { NO_FILE, NO_RELEASES, NOT_FOUND, RATE_LIMITED, NETWORK, OTHER }

data class StarsOutcome(val added: Int, val present: Int, val skipped: List<Pair<String, SkipReason>>)

/**
 * Runs the ordinary detect and add for each picked repository, one at a time so a rate limit is
 * met once. A repository with nothing this device can install is left out and says why.
 */
suspend fun addEach(
    hits: List<SearchHit>,
    detect: suspend (String) -> Detection,
    add: suspend (Detection.Found) -> String,
    onProgress: (done: Int, soFar: StarsOutcome) -> Unit = { _, _ -> },
): StarsOutcome {
    var added = 0
    var present = 0
    val skipped = ArrayList<Pair<String, SkipReason>>()
    for ((index, hit) in hits.withIndex()) {
        val label = hit.owner?.let { "$it/${hit.name}" } ?: hit.name
        try {
            when (val found = detect(hit.url)) {
                is Detection.Found -> when {
                    found.alreadyTracked != null -> present++
                    found.file == null -> skipped += label to SkipReason.NO_FILE
                    else -> {
                        add(found)
                        added++
                    }
                }
                is Detection.Failed -> skipped += label to reasonFor(found.problem.kind)
                is Detection.Results -> skipped += label to SkipReason.OTHER
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            skipped += label to SkipReason.OTHER
        }
        onProgress(index + 1, StarsOutcome(added, present, skipped.toList()))
    }
    return StarsOutcome(added, present, skipped)
}

fun reasonFor(kind: ProblemKind): SkipReason = when (kind) {
    ProblemKind.NO_FILE_FOR_DEVICE -> SkipReason.NO_FILE
    ProblemKind.NO_RELEASES -> SkipReason.NO_RELEASES
    ProblemKind.NOT_FOUND -> SkipReason.NOT_FOUND
    ProblemKind.RATE_LIMITED -> SkipReason.RATE_LIMITED
    ProblemKind.NETWORK -> SkipReason.NETWORK
    else -> SkipReason.OTHER
}

/** Skipped entries grouped by reason, largest group first, so two hundred of them still read as a few lines. */
fun <R> groupSkipped(skipped: List<Pair<String, R>>): List<Pair<R, List<String>>> =
    skipped.groupBy({ it.second }, { it.first }).toList().sortedByDescending { it.second.size }
