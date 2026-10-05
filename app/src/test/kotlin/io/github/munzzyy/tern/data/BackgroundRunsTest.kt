package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new job starts its clock again, and a start of Tern that keeps the job keeps its clock too. */
class BackgroundRunsTest {
    private val s = Settings(checkEveryMinutes = 360)

    @Test
    fun aStartThatKeepsTheJobKeepsItsClock() {
        assertFalse(BackgroundRuns.setsAnew(had = true, before = null, after = s, known = true))
        assertFalse("settings saved unchanged keep the clock", BackgroundRuns.setsAnew(had = true, before = s, after = s, known = true))
        assertFalse("a setting the job is not built from keeps the clock", BackgroundRuns.setsAnew(had = true, before = s, after = s.copy(autoInstalls = false), known = true))
    }

    @Test
    fun aJobSetAnewStartsItsClockAgain() {
        assertTrue("there was no job", BackgroundRuns.setsAnew(had = false, before = null, after = s, known = true))
        assertTrue("nothing was ever noted", BackgroundRuns.setsAnew(had = true, before = null, after = s, known = false))
        assertTrue(BackgroundRuns.setsAnew(had = true, before = s, after = s.copy(checkEveryMinutes = 15), known = true))
        assertTrue(BackgroundRuns.setsAnew(had = true, before = s, after = s.copy(checkOnlyOnUnmetered = true), known = true))
        assertTrue(BackgroundRuns.setsAnew(had = true, before = s, after = s.copy(checkOnlyWhileCharging = true), known = true))
    }
}
