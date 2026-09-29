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
        assertSame(config, withCategory(config, "work"))
        assertEquals(listOf("Work", "Games"), withCategory(config, "Games").categories)
        assertEquals("Work", canonicalCategory("WORK", listOf("Games", "Work")))
        assertEquals("New", canonicalCategory("New", listOf("Games")))
    }
}
