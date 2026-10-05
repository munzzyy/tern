package io.github.munzzyy.tern.core.text

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.select.AssetPolicyException
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.HtmlSource
import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SafePatternTest {
    @Test
    fun matchesAndExtracts() {
        assertTrue(SafePattern.compile("^android-").matches("Android-v4"))
        assertFalse(SafePattern.compile("^android-").matches("desktop-v4"))
        assertFalse(SafePattern.compile("x").matches(null))
        assertEquals("1.2.3", SafePattern.compile("v(\\d+\\.\\d+\\.\\d+)").extract("build-7-v1.2.3"))
        assertEquals("1.2", SafePattern.compile("\\d+\\.\\d+").extract("app 1.2 final"))
        assertNull(SafePattern.compile("v(\\d+)").extract("nothing here"))
        assertNull(SafePattern.compileOrNull("  "))
    }

    @Test
    fun theLastMatchIsTheOneExtractedAsObtainiumTakesIt() {
        assertEquals("1.3", SafePattern.compile("v(\\d+\\.\\d+)").extract("v1.2 is out, then v1.3"))
        assertEquals("20260930", SafePattern.compile("\\d{8}").extract("app-20260101.apk from 20260930"))
    }

    @Test
    fun aPatternForPagesReadsFarBeyondTheUsualLimit() {
        val page = "x".repeat(SafePattern.MAX_INPUT * 10) + "Version: 20260930"
        assertNull(SafePattern.compile("Version: (\\d+)").extract(page))
        assertEquals("20260930", SafePattern.compileForPages("Version: (\\d+)").extract(page))
    }

    @Test
    fun ordinaryPatternsFitTheBudgetOnTheLongestInput() {
        val text = "release notes line with words and 1.2.3 numbers\n".repeat(200)
        for (pattern in listOf("security", "\\bfix(es|ed)?\\b", "^.*android.*$", "(\\d+)\\.(\\d+)\\.(\\d+)", "[a-z]+-[a-z]+", "(?s).*numbers.*")) {
            SafePattern.compile(pattern).matches(text)
        }
    }

    @Test
    fun matchingStopsWhenItsStepsRunOut() {
        val text = "word ".repeat(400)
        val stopped = assertThrows(PatternException::class.java) { SafePattern.compile("(\\w+ )+!", maxSteps = 200).matches(text) }
        assertEquals("(\\w+ )+!", stopped.pattern)
        assertFalse(SafePattern.compile("(\\w+ )+!", maxSteps = 200_000).matches(text))
    }

    @Test(timeout = 5000)
    fun workThatNeverEndsIsLeftBehindAtTheDeadline() {
        val started = System.nanoTime()
        val stopped = assertThrows(PatternException::class.java) {
            SafePattern.watched("stuck", timeoutMs = 150) {
                while (true) Thread.onSpinWait()
            }
        }
        assertEquals("stuck", stopped.pattern)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
    }

    @Test
    fun manyMatchesShareOneDeadlineAndOneThread() {
        val pattern = SafePattern.compile("\\.apk$")
        val threads = HashSet<String>()
        val hits = SafePattern.watched("batch") {
            (1..5000).count { i ->
                threads.add(Thread.currentThread().name)
                pattern.matches(if (i % 2 == 0) "file$i.apk" else "file$i.txt")
            }
        }
        assertEquals(2500, hits)
        assertEquals(1, threads.size)
    }

    @Test
    fun whatTheWorkThrowsReachesTheCaller() {
        assertThrows(IllegalStateException::class.java) { SafePattern.watched("x") { error("from the body") } }
        assertThrows(PatternException::class.java) { SafePattern.watched("x") { SafePattern.compile("(unclosed") } }
    }

    @Test
    fun refusesWhatCannotBeCompiled() {
        assertThrows(PatternException::class.java) { SafePattern.compile("(unclosed") }
        assertThrows(PatternException::class.java) { SafePattern.compile("a".repeat(501)) }
    }

    @Test
    fun aBrokenFileFilterIsReportedAsOne() {
        val assets = listOf(Asset("app.apk", "https://example.org/a.apk"))
        assertThrows(AssetPolicyException::class.java) { AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(exclude = "(unclosed")) }
        assertThrows(AssetPolicyException::class.java) { AssetPicker.rank(assets, DeviceProfile.ARM64_PHONE, AssetPolicy(include = "a".repeat(501))) }
    }

    @Test
    fun aBrokenLinkFilterIsReportedAsOne() {
        val page = "https://example.org/get"
        val spec = SourceSpec(SourceTypes.HTML, page, mapOf(SourceOptions.LINK_FILTER to "(unclosed"))
        val stopped = assertThrows(SourceException::class.java) {
            HtmlSource().check(spec, CheckContext(FakeHttp().text(page, "<a href=\"/a.apk\">x</a>"), InMemoryValidatorStore()))
        }
        assertEquals(SourceErrorKind.UNSUPPORTED, stopped.kind)
    }

    private val released = AtomicBoolean(false)

    @After
    fun releaseTheSpinners() {
        released.set(true)
    }

    private fun spin(): Int {
        while (!released.get()) Thread.onSpinWait()
        return 0
    }

    private fun untilNoMoreThan(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (SafePattern.stillRunning() > count && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(count, SafePattern.stillRunning())
    }

    @Test(timeout = 10_000)
    fun aPatternStillRunningPastItsDeadlineIsNotStartedAgain() {
        val before = SafePattern.stillRunning()
        val pattern = "(x+x+)+y-single"
        assertThrows(PatternException::class.java) { SafePattern.matching(pattern, timeoutMs = 100) { spin() } }
        assertEquals(before + 1, SafePattern.stillRunning())

        var ran = false
        val started = System.nanoTime()
        val refused = assertThrows(PatternException::class.java) { SafePattern.matching(pattern, timeoutMs = 100) { ran = true } }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 50)
        assertFalse(ran)
        assertEquals(pattern, refused.pattern)
        assertEquals(before + 1, SafePattern.stillRunning())
        assertEquals(42, SafePattern.matching("another-single", timeoutMs = 100) { 42 })

        released.set(true)
        untilNoMoreThan(before)
        assertEquals(7, SafePattern.matching(pattern, timeoutMs = 100) { 7 })
    }

    @Test(timeout = 10_000)
    fun aBatchNamesThePatternItWasMatchingWhenTheDeadlinePassed() {
        val before = SafePattern.stillRunning()
        val pattern = "(x+x+)+y-batch"
        assertThrows(PatternException::class.java) {
            SafePattern.watched("release filters", timeoutMs = 100) { SafePattern.matching(pattern) { spin() } }
        }
        var ran = false
        val started = System.nanoTime()
        val refused = assertThrows(PatternException::class.java) {
            SafePattern.watched("release filters", timeoutMs = 100) { SafePattern.matching(pattern) { ran = true } }
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 50)
        assertFalse(ran)
        assertEquals(pattern, refused.pattern)
        assertEquals(3, SafePattern.watched("release filters", timeoutMs = 100) { SafePattern.matching("fine-batch") { 3 } })
        released.set(true)
        untilNoMoreThan(before)
    }

    @Test(timeout = 10_000)
    fun onceAFewAreLeftRunningNothingMoreIsMatched() {
        val before = SafePattern.stillRunning()
        assertTrue(before < SafePattern.MAX_LEFT_RUNNING)
        repeat(SafePattern.MAX_LEFT_RUNNING - before) { n ->
            assertThrows(PatternException::class.java) { SafePattern.watched("stuck $n", timeoutMs = 50) { spin() } }
        }
        assertEquals(SafePattern.MAX_LEFT_RUNNING, SafePattern.stillRunning())
        val refused = assertThrows(PatternException::class.java) { SafePattern.compile("a").matches("a") }
        assertTrue(refused.message!!, "until Tern restarts" in refused.message!!)
        assertThrows(PatternException::class.java) { SafePattern.watched<String>("batch") { "never" } }

        released.set(true)
        untilNoMoreThan(before)
        assertTrue(SafePattern.compile("a").matches("a"))
    }
}
