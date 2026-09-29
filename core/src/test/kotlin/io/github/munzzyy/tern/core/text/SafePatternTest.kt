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
import org.junit.Test

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
}
