package io.github.munzzyy.jackdaw.enginetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.model.ReleasePolicy
import io.github.munzzyy.jackdaw.core.text.PatternException
import io.github.munzzyy.jackdaw.core.text.SafePattern
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

    private companion object {
        const val TAG = "EnginePattern"
    }
}
