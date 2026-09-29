package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.core.interop.StampExport
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.engine.real.ImportTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Names each sentence instead of writing it, so a test can tell which one was chosen. */
object ImportSentences : ImportTexts {
    override fun importNotAnExport() = "importNotAnExport"
    override fun importEmpty() = "importEmpty"
    override fun importUnreadableExport(detail: String?) = "importUnreadableExport($detail)"
    override fun importTooLarge(limitBytes: Int) = "importTooLarge($limitBytes)"
    override fun linkNotHttps() = "linkNotHttps"
    override fun linkNotAnAddress() = "linkNotAnAddress"
    override fun linkLeavesHttps() = "linkLeavesHttps"
    override fun linkNotFound() = "linkNotFound"
    override fun linkRefused(status: Int) = "linkRefused($status)"
    override fun linkUnreachable(detail: String?) = "linkUnreachable($detail)"
    override fun linkTooSlow() = "linkTooSlow"
    override fun linkNotAnExport() = "linkNotAnExport"
    override fun serverStatus(code: Int) = "serverStatus($code)"
    override fun checkRateLimited(untilMs: Long?) = "checkRateLimited($untilMs)"
}

fun exportOf(vararg names: String): String = StampExport.write(
    names.map { AppConfig(it.lowercase(), SourceSpec(SourceTypes.GITHUB, "https://github.com/example/${it.lowercase()}"), it) },
    0,
    "test",
)

fun refused(kind: ProblemKind, sentence: String, call: () -> Any?) {
    val answer = try {
        call()
    } catch (e: ProblemException) {
        assertEquals(Problem(kind, sentence), e.problem.copy(retryAtMs = null))
        return
    }
    fail("was not refused, and answered $answer")
}
