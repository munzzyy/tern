package io.github.munzzyy.tern.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.PatternProblem
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.Evaluator
import io.github.munzzyy.tern.engine.real.Texts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Measures user-written patterns on Android's own regex engine, where only the deadline can stop a runaway. */
@RunWith(AndroidJUnit4::class)
class PatternTest {
    private val patterns = listOf(
        "^(a+)+$" to { n: Int -> "a".repeat(n) + "!" },
        "(x+x+)+y" to { n: Int -> "x".repeat(n) },
        "(a|aa)+$" to { n: Int -> "a".repeat(n) + "!" },
        "^(\\w+\\s?)+$" to { n: Int -> "a".repeat(n) + "!" },
        "(.*a){12}" to { n: Int -> "a".repeat(11) + "b".repeat(maxOf(0, n - 11)) },
    )

    @Test
    fun measureEveryRunawayPatternAgainstBait() {
        val rows = ArrayList<String>()
        for ((pattern, bait) in patterns) {
            for (n in listOf(30, 60, 200)) {
                val safe = SafePattern.compile(pattern)
                val start = System.nanoTime()
                val outcome = try {
                    "matched=${safe.matches(bait(n))}"
                } catch (e: PatternException) {
                    "deadline fired (${e.message})"
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                rows += "| `$pattern` | $n | $ms ms | $outcome |"
                assertTrue("$pattern on $n chars took $ms ms", ms < SafePattern.SINGLE_TIMEOUT_MS + 1_000)
            }
        }
        rows.forEach { Log.i(TAG, it) }
    }

    @Test
    fun aRunawayFilterBecomesAProblemOnTheRowAndIsNotRetried() = runBlocking {
        Harness("pattern").use { h ->
            h.forge.releases = listOf(FakeForge.Release("a".repeat(40) + "!", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.configure(id) { it.copy(releases = ReleasePolicy(tagFilter = "^(a+)+$")) }

            val first = System.nanoTime()
            h.engine.check(id)
            val firstMs = (System.nanoTime() - first) / 1_000_000
            assertEquals(AppStatus.ERROR, h.row(id).status)
            assertEquals(ProblemKind.PARSE, h.row(id).problem?.kind)
            assertNotNull(h.state(id).patternProblem)

            val second = System.nanoTime()
            h.engine.check(id)
            val secondMs = (System.nanoTime() - second) / 1_000_000
            Log.i(TAG, "check with a runaway filter: first ${firstMs} ms, second ${secondMs} ms")
            assertEquals(AppStatus.ERROR, h.row(id).status)
            assertTrue("the filter was tried again: $secondMs ms", secondMs < SafePattern.BATCH_TIMEOUT_MS)

            h.engine.configure(id) { it.copy(releases = ReleasePolicy(tagFilter = "^a+!$")) }
            h.engine.check(id)
            assertNull(h.state(id).patternProblem)
            assertEquals(AppStatus.NOT_INSTALLED, h.row(id).status)
        }
    }

    @Test
    fun aFilterRememberedToFailIsNotRunToListTheVersions() = runBlocking {
        Harness("pattern-listed").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v1", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
            val id = h.addFixture()
            h.engine.configure(id) { it.copy(releases = ReleasePolicy(versionExtract = "v(\\d+)")) }
            h.engine.check(id)
            assertEquals(listOf("1"), h.engine.releases(id).map { it.version })
            assertEquals(listOf("1"), h.engine.offerable(id).map { it.version })

            val filters = Evaluator.filtersKey(h.engine.evaluator.effective(h.engine.store.app(id)!!.config))
            h.engine.saveState(id) { it.copy(patternProblem = PatternProblem(filters, "too long")) }
            assertEquals(listOf("v1"), h.engine.releases(id).map { it.version })
            assertEquals(emptyList<String>(), h.engine.offerable(id).map { it.version })
        }
    }

    @Test
    fun aFilterTurnedAwayWhileOthersRunIsNotRemembered() {
        val config = AppConfig("busy", SourceSpec(SourceTypes.FORGEJO, FakeForge.PROJECT), "Busy", releases = ReleasePolicy(tagFilter = "^v"))
        val state = AppState(releases = listOf(Release(id = "v1", version = "1", assets = listOf(Asset("app.apk", "https://example.org/app.apk")))), lastCheckedMs = 0)
        val released = AtomicBoolean(false)
        try {
            repeat(SafePattern.MAX_LEFT_RUNNING) {
                try {
                    SafePattern.watched("stuck", timeoutMs = 50) { while (!released.get()) Thread.yield() }
                } catch (_: PatternException) {
                }
            }
            val eval = Evaluator(Texts(targetContext), DeviceProfile.ARM64_PHONE) { 0L }.evaluate(config, state, null) { _, _ -> null }
            assertEquals(AppStatus.ERROR, eval.status)
            assertEquals(ProblemKind.PARSE, eval.problem?.kind)
            assertNull(eval.patternProblem)
        } finally {
            released.set(true)
        }
    }

    private companion object {
        const val TAG = "EnginePattern"
    }
}
