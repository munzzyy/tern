package io.github.munzzyy.stamp.ui.importing

import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.engine.Detection
import io.github.munzzyy.stamp.engine.FileChoice
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StarsImportTest {
    private fun hit(name: String) = SearchHit(name, "example", null, "https://github.com/example/$name", "GitHub", 1)

    private fun found(url: String, file: Boolean = true, tracked: String? = null) = Detection.Found(
        spec = SourceSpec("github", url), name = url.substringAfterLast('/'), author = null, description = null, release = null,
        file = if (file) FileChoice(Asset("app.apk", "$url/app.apk"), emptyList()) else null,
        otherFiles = emptyList(), verification = null, installed = null, alreadyTracked = tracked, warnings = emptyList(),
    )

    private fun failed(kind: ProblemKind) = Detection.Failed(Problem(kind, "no"))

    @Test
    fun addsOnlyWhatHasAFileAndSaysWhyTheRestWasLeft() = runBlocking {
        val answers = mapOf(
            "app" to found("https://github.com/example/app"),
            "tool" to found("https://github.com/example/tool", file = false),
            "known" to found("https://github.com/example/known", tracked = "id1"),
            "gone" to failed(ProblemKind.NOT_FOUND),
            "empty" to failed(ProblemKind.NO_RELEASES),
            "busy" to failed(ProblemKind.RATE_LIMITED),
            "odd" to Detection.Results("odd", emptyList()),
        )
        val added = ArrayList<String>()
        val progress = ArrayList<Int>()
        val outcome = addEach(
            answers.keys.map(::hit),
            detect = { url -> answers.getValue(url.substringAfterLast('/')) },
            add = { f -> added += f.spec.url; "id" },
            onProgress = { done, _ -> progress += done },
        )
        assertEquals(listOf("https://github.com/example/app"), added)
        assertEquals(1, outcome.added)
        assertEquals(1, outcome.present)
        assertEquals(
            listOf(
                "example/tool" to SkipReason.NO_FILE, "example/gone" to SkipReason.NOT_FOUND, "example/empty" to SkipReason.NO_RELEASES,
                "example/busy" to SkipReason.RATE_LIMITED, "example/odd" to SkipReason.OTHER,
            ),
            outcome.skipped,
        )
        assertEquals((1..7).toList(), progress)
    }

    @Test
    fun aFailingAddIsReportedNotThrown() = runBlocking {
        val outcome = addEach(
            listOf(hit("app")),
            detect = { found(it) },
            add = { throw IllegalArgumentException("bad pattern") },
        )
        assertEquals(0, outcome.added)
        assertEquals(listOf("example/app" to SkipReason.OTHER), outcome.skipped)
    }

    @Test
    fun twoHundredSkippedReadAsAFewGroupsLargestFirst() {
        val skipped = (1..190).map { "example/tool-$it" to "no file" } + (1..10).map { "example/gone-$it" to "not found" } + listOf("example/x" to "other")
        val groups = groupSkipped(skipped)
        assertEquals(listOf("no file", "not found", "other"), groups.map { it.first })
        assertEquals(190, groups[0].second.size)
        assertEquals("example/tool-1", groups[0].second.first())
    }

    @Test
    fun everyProblemKindHasAReason() {
        assertEquals(SkipReason.NO_FILE, reasonFor(ProblemKind.NO_FILE_FOR_DEVICE))
        assertEquals(SkipReason.NETWORK, reasonFor(ProblemKind.NETWORK))
        assertEquals(SkipReason.OTHER, reasonFor(ProblemKind.AUTH))
    }
}
