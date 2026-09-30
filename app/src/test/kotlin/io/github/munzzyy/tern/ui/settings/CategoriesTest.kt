package io.github.munzzyy.tern.ui.settings

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.data.CategoryColors
import io.github.munzzyy.tern.ui.testRow
import io.github.munzzyy.tern.ui.theme.CATEGORY_SWATCHES
import io.github.munzzyy.tern.ui.theme.categoryArgb
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    private val rows = listOf(
        testRow(id = "a", categories = listOf("Maps", "work")),
        testRow(id = "b", categories = listOf(" Work ")),
        testRow(id = "c"),
    )

    @Test
    fun everyCategoryIsNamedOnceWhereverItIsNamed() {
        assertEquals(listOf("Games", "Maps", "work"), allCategories(rows, mapOf("Games" to 1, "WORK" to 2), Locale.US))
        assertEquals(2, appsIn(rows, "WORK"))
        assertEquals(0, appsIn(rows, "Games"))
    }

    @Test
    fun aRenameReachesEveryAppAndKeepsEachCategoryOnce() {
        assertEquals(listOf("Maps", "Office"), renamedCategories(listOf("Maps", "work"), "Work", "Office"))
        assertEquals(listOf("Office"), renamedCategories(listOf("work", "Office"), "work", "Office"))
        assertEquals(listOf("Maps"), withoutCategory(listOf("Maps", " WORK"), "work"))
    }

    @Test
    fun aColourMovesWithItsCategory() {
        assertEquals(mapOf("Maps" to 1, "Office" to 9), recolored(mapOf("Maps" to 1, "work" to 2), "Work", "Office", 9))
        assertEquals(mapOf("Maps" to 1, "New" to 3), recolored(mapOf("Maps" to 1), null, "New", 3))
    }

    @Test
    fun aNameAlreadyTakenIsRefusedButItsOwnNameIsNot() {
        val names = listOf("Maps", "Work")
        assertTrue(categoryTaken("maps", null, names))
        assertFalse(categoryTaken("MAPS", "Maps", names))
        assertTrue(categoryTaken("work", "Maps", names))
    }

    @Test
    fun aCategoryWithoutAColourTakesTheSameOneEveryTime() {
        assertEquals(categoryArgb("Maps", emptyMap()), categoryArgb("maps", emptyMap()))
        assertTrue(categoryArgb("Maps", emptyMap()) in CATEGORY_SWATCHES)
        assertEquals(42, categoryArgb("Maps", mapOf("MAPS" to 42)))
        val taken = CATEGORY_SWATCHES.take(3)
        assertEquals(CATEGORY_SWATCHES[3], unusedSwatch(listOf("a", "b", "c"), mapOf("a" to taken[0], "b" to taken[1], "c" to taken[2])))
    }

    @Test
    fun coloursAreReadAsObtainiumWritesThem() {
        val colors = CategoryColors.decode(Json.parseObject("""{"Work":4294198070,"Games":-16776961,"  ":1,"Bad":"red","Maps":99999999999}"""))
        assertEquals(mapOf("Work" to 4294198070L.toInt(), "Games" to -16776961), colors)
        assertEquals(colors, CategoryColors.decode(CategoryColors.encode(colors)))
    }
}
