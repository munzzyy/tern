package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.ui.importing.StarsState
import io.github.munzzyy.tern.ui.importing.pickedShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterAndReadingTest {
    private fun hit(name: String, owner: String? = null, description: String? = null, origin: String = "GitHub") =
        SearchHit(name, owner, description, "https://github.com/${owner ?: "example"}/$name", origin)

    private val hits = listOf(
        hit("Organic Maps", "organicmaps", "Offline maps for travellers"),
        hit("Feeder", "spacecowboy", "A feed reader", origin = "Codeberg"),
        hit("C++ Notes", "someone", "Notes about C++ (and more)"),
    )

    @Test
    fun theFilterTakesWordsOrAPatternWhateverTheCase() {
        assertEquals(hits, filterHits(hits, "  "))
        assertEquals(listOf("Organic Maps"), filterHits(hits, "MAPS").map { it.name })
        assertEquals(listOf("Organic Maps", "Feeder"), filterHits(hits, "^(organic|feed)").map { it.name })
        assertEquals(listOf("Feeder"), filterHits(hits, "codeberg").map { it.name })
        assertEquals(listOf("Feeder"), filterHits(hits, "spacecowboy").map { it.name })
    }

    @Test
    fun whatIsNoPatternIsTakenAsTheWordsItIs() {
        assertEquals(listOf("C++ Notes"), filterHits(hits, "c++ (and").map { it.name })
        assertEquals(emptyList<SearchHit>(), filterHits(hits, "(unclosed"))
    }

    @Test
    fun theOptionsAreOfferedForAnAddressAndNotForWords() {
        assertTrue(readsAnAddress("https://example.org/downloads", AddState.Idle))
        assertTrue(readsAnAddress("example.org", AddState.Looking("example.org")))
        assertFalse(readsAnAddress("feed reader", AddState.Idle))
        assertFalse(readsAnAddress("", AddState.Idle))
        val carried = AddState.Answer(Detection.Results(Detection.Results.CARRIED, emptyList()))
        assertFalse(readsAnAddress("obtainium://apps/[]", carried))
    }

    @Test
    fun aFailedPageOffersItsOptionsUnlessTheConnectionFailed() {
        val page = SourceSpec(SourceTypes.HTML, "https://example.org/downloads")
        assertTrue(optionsMayHelp(Detection.Failed(Problem(ProblemKind.NO_RELEASES, "none"), page)))
        assertFalse(optionsMayHelp(Detection.Failed(Problem(ProblemKind.NETWORK, "down"), page)))
        assertFalse(optionsMayHelp(Detection.Failed(Problem(ProblemKind.NO_RELEASES, "none"))))
        assertFalse(optionsMayHelp(Detection.Failed(Problem(ProblemKind.NOT_FOUND, "gone"), SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/a/b"))))
    }

    @Test
    fun everyKindAPersonMayPickIsOfferedAndVivoIsNot() {
        assertEquals(null, READ_AS_CHOICES.first())
        for (type in listOf(SourceTypes.HTML, SourceTypes.DIRECT, SourceTypes.GITHUB, SourceTypes.GITLAB, SourceTypes.FORGEJO, SourceTypes.FDROID_REPO, SourceTypes.JENKINS)) {
            assertTrue(type, type in READ_AS_CHOICES)
        }
        assertFalse(SourceTypes.VIVO in READ_AS_CHOICES)
    }

    @Test
    fun withStoresOffNoStoreIsOfferedToReadAnAddressAs() {
        assertTrue(SourceTypes.APKPURE in readAsChoices(storesOn = true))
        val off = readAsChoices(storesOn = false)
        assertTrue(off.none { it in SourceTypes.THIRD_PARTY_STORES })
        assertTrue(SourceTypes.ITCHIO in off && SourceTypes.HTML in off && null in off)
    }

    @Test
    fun aPackageNameIsOneAndroidTakesOrNone() {
        assertTrue(isPackageName(""))
        assertTrue(isPackageName("org.example.app"))
        assertFalse(isPackageName("example"))
        assertFalse(isPackageName("org.example.1app"))
    }

    @Test
    fun tickingAllTicksWhatIsShownAndLeavesWhatIsHidden() {
        val all = hits
        val listed = StarsState.Listed("", all, picked = setOf(all[2].url), filter = "maps", shown = all.take(1))
        assertEquals(setOf(all[0].url, all[2].url), pickedShown(listed, on = true))
        val everything = listed.copy(picked = all.map { it.url }.toSet())
        assertEquals(setOf(all[1].url, all[2].url), pickedShown(everything, on = false))
    }
}
