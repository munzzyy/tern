package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsTest {
    private fun hit(name: String, origin: String) = SearchHit(name, null, null, "https://apps.example.org/repo?package=org.example.$name", origin)

    @Test
    fun aListThatCameFromAnAddressIsARepository() {
        assertTrue(isRepository(Detection.Results("https://apps.example.org/fdroid/repo", emptyList())))
        assertTrue(isRepository(Detection.Results("  HTTPS://apps.example.org/fdroid/repo", emptyList())))
        assertFalse(isRepository(Detection.Results("feed reader", emptyList())))
        assertFalse(isRepository(Detection.Results("http://apps.example.org", emptyList())))
        assertFalse(isRepository(Detection.Results("https", emptyList())))
    }

    @Test
    fun aRepositoryIsCalledByItsOwnNameOrElseByItsHost() {
        val named = Detection.Results("https://apps.example.org/fdroid/repo", listOf(hit("a", "Example Apps"), hit("b", "Example Apps")))
        assertEquals("Example Apps", repositoryName(named))
        val nameless = Detection.Results("https://apps.example.org/fdroid/repo", listOf(hit("a", "  ")))
        assertEquals("apps.example.org", repositoryName(nameless))
        assertEquals("apps.example.org", repositoryName(Detection.Results("https://Apps.Example.org/fdroid/repo", emptyList())))
    }

    @Test
    fun theListSaysWhenItIsNotAllThereIs() {
        val few = listOf(hit("a", "Example Apps"))
        assertFalse(isCut(Detection.Results("https://apps.example.org/repo", few)))
        assertTrue(isCut(Detection.Results("https://apps.example.org/repo", few, more = true)))
        val many = (1..MAX_RESULTS + 1).map { hit("app$it", "Example Apps") }
        assertTrue(isCut(Detection.Results("https://apps.example.org/repo", many)))
        assertFalse(isCut(Detection.Results("https://apps.example.org/repo", many.take(MAX_RESULTS))))
    }

    @Test
    fun aDescriptionIsCutBetweenTwoWordsAndSaysSo() {
        assertEquals("A small reader.", brief("  A small\n reader.  "))
        assertEquals("one two\u2026", brief("one two three four", limit = 10))
        assertEquals("abcdefghij\u2026", brief("abcdefghijklmnopqrstuvwxyz", limit = 10))
        val long = brief("word ".repeat(500))
        assertTrue(long.length <= MAX_DESCRIPTION + 1)
        assertTrue(long.endsWith("word\u2026"))
        assertEquals("", brief("   "))
    }

    @Test
    fun thePhoneIsOfferedWhereTypingOrPickingAFileIsHard() {
        assertTrue(offersHandoff(noTouch = true, filePicker = true))
        assertTrue(offersHandoff(noTouch = false, filePicker = false))
        assertTrue(offersHandoff(noTouch = true, filePicker = false))
        assertFalse(offersHandoff(noTouch = false, filePicker = true))
    }
}
