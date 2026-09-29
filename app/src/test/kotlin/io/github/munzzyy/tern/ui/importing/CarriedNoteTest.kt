package io.github.munzzyy.tern.ui.importing

import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Test

class CarriedNoteTest {
    @Test
    fun eachNameLinksToADifferentAppPreferringOneThatFits() {
        val rows = listOf(testRow(id = "a", name = "Twin"), testRow(id = "b", name = "Twin"), testRow(id = "c", name = "Solo"))
        val links = linkNames(listOf("Twin", "Twin", "Solo", "Gone", "Twin"), rows) { it.id == "b" }
        assertEquals(listOf("Twin" to "b", "Twin" to "a", "Solo" to "c", "Gone" to null, "Twin" to null), links)
    }
}
