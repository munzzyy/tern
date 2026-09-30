package io.github.munzzyy.tern.ui.apps

import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BulkTest {
    @Test
    fun anUpdateTouchesOnlyAppsThatCanUpdateNow() {
        val ready = testRow(id = "ready", status = AppStatus.UPDATE_AVAILABLE, offered = "2.0")
        val busy = testRow(id = "busy", status = AppStatus.UPDATE_AVAILABLE, offered = "2.0", progress = Progress(Phase.DOWNLOADING))
        val watched = testRow(id = "watched", status = AppStatus.NEW_RELEASE, trackOnly = true)
        val current = testRow(id = "current")
        val all = listOf(ready, busy, watched, current)
        assertEquals(listOf("ready"), touched(BulkAction.UPDATE, all).map { it.id })
        for (action in listOf(BulkAction.CATEGORY, BulkAction.CHECK, BulkAction.REMOVE)) assertEquals(all, touched(action, all))
    }

    @Test
    fun categoryNamesAreTrimmedCappedAndCleaned() {
        assertEquals("Reading", cleanCategory("  Reading \n"))
        assertEquals("ab", cleanCategory("a‮b\u0000"))
        assertEquals(MAX_CATEGORY, cleanCategory("x".repeat(500))?.length)
        assertNull(cleanCategory("   "))
        assertNull(cleanCategory("​\u0007"))
    }

    @Test
    fun anAppIsFiledUnderACategoryOnceWhateverTheCase() {
        val config = testRow(categories = listOf("Work")).config
        assertSame(config, withCategories(config, mapOf("work" to Filed.ALL)))
        assertEquals(listOf("Work", "Games"), withCategories(config, mapOf("Games" to Filed.ALL)).categories)
        assertEquals("Work", canonicalCategory("WORK", listOf("Games", "Work")))
        assertEquals("New", canonicalCategory("New", listOf("Games")))
    }

    @Test
    fun categoriesOfManyAppsAreSetTakenOutOrLeftAsTheyAre() {
        val both = testRow(id = "b", categories = listOf("Work", "Games"))
        val one = testRow(id = "o", categories = listOf("Work"))
        val none = testRow(id = "n")
        val picked = listOf(both, one, none)
        assertEquals(Filed.SOME, filedUnder(picked, "work"))
        assertEquals(Filed.ALL, filedUnder(listOf(both, one), "Work"))
        assertEquals(Filed.NONE, filedUnder(picked, "Maps"))
        // Games taken out of all, Maps added to all, Work left as each app has it.
        val chosen = mapOf("Games" to Filed.NONE, "Maps" to Filed.ALL, "Work" to Filed.SOME)
        assertEquals(listOf("Work", "Maps"), withCategories(both.config, chosen).categories)
        assertEquals(listOf("Work", "Maps"), withCategories(one.config, chosen).categories)
        assertEquals(listOf("Maps"), withCategories(none.config, chosen).categories)
        // Replacing is ticking what is wanted and emptying the rest.
        assertEquals(listOf("Maps"), withCategories(both.config, mapOf("Work" to Filed.NONE, "Games" to Filed.NONE, "Maps" to Filed.ALL)).categories)
    }

    @Test
    fun aBoxGoesBackToSomeOnlyWhereItStartedThere() {
        assertEquals(Filed.ALL, nextFiled(Filed.SOME, Filed.SOME))
        assertEquals(Filed.NONE, nextFiled(Filed.ALL, Filed.SOME))
        assertEquals(Filed.SOME, nextFiled(Filed.NONE, Filed.SOME))
        assertEquals(Filed.ALL, nextFiled(Filed.NONE, Filed.NONE))
        assertEquals(Filed.NONE, nextFiled(Filed.ALL, Filed.ALL))
        assertEquals(Filed.ALL, nextFiled(Filed.NONE, Filed.ALL))
    }

    @Test
    fun anInstallTouchesOnlyAppsThatAreNotInstalledAndHaveAFile() {
        val ready = testRow(id = "ready", status = AppStatus.NOT_INSTALLED, installed = null)
        val noFile = testRow(id = "nofile", status = AppStatus.NOT_INSTALLED, installed = null, withFile = false)
        val tracked = testRow(id = "tracked", status = AppStatus.NOT_INSTALLED, installed = null, trackOnly = true)
        val busy = testRow(id = "busy", status = AppStatus.NOT_INSTALLED, installed = null, progress = Progress(Phase.DOWNLOADING))
        val installed = testRow(id = "installed", status = AppStatus.UPDATE_AVAILABLE, offered = "2.0")
        assertEquals(listOf("ready"), touched(BulkAction.INSTALL, listOf(ready, noFile, tracked, busy, installed)).map { it.id })
        assertEquals(listOf("installed"), touched(BulkAction.UPDATE, listOf(ready, installed)).map { it.id })
    }
}
