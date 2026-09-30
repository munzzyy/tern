package io.github.munzzyy.tern.ui.add

import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlacesTest {
    @Test
    fun aPlaceIsSwitchedButTheLastOneStays() {
        assertEquals(setOf("GitHub", "F-Droid"), toggledPlaces(setOf("GitHub"), "F-Droid"))
        assertEquals(setOf("GitHub"), toggledPlaces(setOf("GitHub", "F-Droid"), "F-Droid"))
        assertEquals(setOf("GitHub"), toggledPlaces(setOf("GitHub"), "GitHub"))
    }

    @Test
    fun wherePlacesAreOfferedOnlyWhenWordsAreSearched() {
        assertTrue(searchesByName("maps", AddState.Idle))
        assertTrue(searchesByName("organic maps", AddState.Idle))
        assertFalse(searchesByName("", AddState.Idle))
        assertFalse(searchesByName("https://github.com/a/b", AddState.Idle))
        assertFalse(searchesByName("github.com/a/b", AddState.Idle))
        assertFalse(searchesByName("f-droid.org", AddState.Idle))
        val found = Detection.Results("maps", listOf(SearchHit("Maps", null, null, "https://github.com/a/maps", "GitHub")))
        assertTrue(searchesByName("maps", AddState.Answer(found)))
        val repository = Detection.Results("https://apt.izzysoft.de/fdroid/repo", emptyList())
        assertFalse(searchesByName("maps", AddState.Answer(repository)))
    }
}
