package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
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

    private val repository = "https://apps.example.org/fdroid/repo"
    private val fingerprint = "ab".repeat(32)

    private fun repoHit(packageName: String) =
        SearchHit(packageName, null, null, FDroidRepoSource.appAddress(repository, packageName, fingerprint), "Example Apps", type = SourceTypes.FDROID_REPO)

    private fun repoSource(packageName: String) =
        SourceSpec(SourceTypes.FDROID_REPO, repository, mapOf(SourceOptions.PACKAGE to packageName, SourceOptions.FINGERPRINT to fingerprint))

    @Test
    fun aHitIsInTheListWhateverTheCaseAndATrailingSlash() {
        val followed = listOf(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/sparrow"))
        val hit = SearchHit("Sparrow", "Example", null, "https://GitHub.com/Example/Sparrow/", "GitHub")
        assertEquals(setOf(hit.url), followedHits(listOf(hit), followed))
        assertEquals(emptySet<String>(), followedHits(listOf(hit.copy(url = "https://github.com/example/wren")), followed))
    }

    @Test
    fun anAppOfARepositoryIsInTheListOnlyByItsOwnPackage() {
        val followed = listOf(repoSource("org.example.notes"))
        assertEquals(setOf(repoHit("org.example.notes").url), followedHits(listOf(repoHit("org.example.notes"), repoHit("org.example.maps")), followed))
        val elsewhere = repoHit("org.example.notes").copy(url = FDroidRepoSource.appAddress("https://other.example.org/fdroid/repo", "org.example.notes", fingerprint))
        assertEquals(emptySet<String>(), followedHits(listOf(elsewhere), followed))
    }

    @Test
    fun theSameAddressReadAsAnotherKindIsNotTheSameApp() {
        val followed = listOf(SourceSpec(SourceTypes.HTML, "https://github.com/example/sparrow"))
        assertEquals(emptySet<String>(), followedHits(listOf(SearchHit("Sparrow", "Example", null, "https://github.com/example/sparrow", "GitHub")), followed))

        val ownForgejo = SearchHit("Wren", "me", null, "https://git.example.org/me/wren", "git.example.org", type = SourceTypes.FORGEJO)
        assertEquals(setOf(ownForgejo.url), followedHits(listOf(ownForgejo), listOf(SourceSpec(SourceTypes.FORGEJO, "https://git.example.org/me/wren"))))
        assertEquals(emptySet<String>(), followedHits(listOf(ownForgejo), listOf(SourceSpec(SourceTypes.GITHUB, "https://git.example.org/me/wren"))))
    }

    @Test
    fun anAddressNoSourceKnowsIsInNoList() {
        val followed = listOf(SourceSpec(SourceTypes.HTML, "https://example.org/apps"))
        assertEquals(null, sourceOf(SearchHit("Notes", null, null, "obtainium://app/%7B%7D", "Obtainium")))
        assertEquals(emptySet<String>(), followedHits(listOf(SearchHit("Notes", null, null, "obtainium://app/%7B%7D", "Obtainium")), followed))
    }
}
