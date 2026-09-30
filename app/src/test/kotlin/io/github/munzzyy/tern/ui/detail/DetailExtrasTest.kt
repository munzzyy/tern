package io.github.munzzyy.tern.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailExtrasTest {
    @Test
    fun aChipFilesTheAppOnceAndTakesItOutInAnyCase() {
        assertEquals(listOf("Maps", "Work"), toggledCategory(listOf("Maps"), "Work", on = true))
        assertEquals(listOf("Maps", "work"), toggledCategory(listOf("Maps", "work"), "Work", on = true))
        assertEquals(listOf("Maps"), toggledCategory(listOf("Maps", " work"), "Work", on = false))
        val full = (1..MAX_APP_CATEGORIES).map { "c$it" }
        assertEquals(full, toggledCategory(full, "One more", on = true))
    }
}
