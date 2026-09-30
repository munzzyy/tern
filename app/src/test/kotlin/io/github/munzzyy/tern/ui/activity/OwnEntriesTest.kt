package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tern's own messages in the log: entries of their own, shown or hidden by their filter, shared and cut like the rest. */
class OwnEntriesTest {
    private val zone = ZoneOffset.UTC
    private val start = 1_700_000_000_000L

    private val events = listOf(
        Event(1, start, "a", "Maps", EventKind.DOWNLOADED, "Downloaded 8.4 MB"),
        Event(2, start + 1_000, null, null, EventKind.OWN_NOTE, "A check of 3 apps started. It was asked for in Tern."),
        Event(3, start + 2_000, "a", "Maps", EventKind.INSTALLED, "Installed version 2.0 (version code 2)"),
        Event(4, start + 3_000, null, null, EventKind.OWN_WARNING, "Could not switch Obtainium links: denied"),
        Event(5, start + 4_000, "b", "Notes", EventKind.CHECK_FAILED, "GitHub did not answer"),
        Event(6, start + 5_000, null, null, EventKind.OWN_ERROR, "Engine task failed\njava.io.IOException: timeout\nat io.github.munzzyy.tern.A.b(A.kt:3)"),
    )

    private val marks = mapOf(EventKind.OWN_NOTE to "Note from Tern", EventKind.OWN_WARNING to "Warning from Tern", EventKind.OWN_ERROR to "Error from Tern")

    private fun shown(problemsOnly: Boolean = false, own: Boolean = true) = entriesByDay(events, zone, problemsOnly, own).flatMap { it.entries }

    @Test
    fun eachMessageOfTernsOwnIsAnEntryOfItsOwnKind() {
        val entries = shown()
        assertEquals(5, entries.size)
        val own = entries.filter { it.headline == Headline.OWN }
        assertEquals(listOf(6L, 4L, 2L), own.map { it.id })
        assertTrue(own.all { it.steps.size == 1 && it.outcome.appId == null })
        val install = entries.single { it.headline == Headline.INSTALLED }
        assertEquals(listOf(EventKind.DOWNLOADED, EventKind.INSTALLED), install.steps.map { it.kind })
    }

    @Test
    fun theFilterHidesThemAndNothingElse() {
        val hidden = shown(own = false)
        assertEquals(listOf(5L, 3L), hidden.map { it.id })
        assertTrue(hidden.none { it.headline == Headline.OWN })
        assertEquals(shown().filter { it.headline != Headline.OWN }, hidden)
    }

    @Test
    fun problemsOnlyTakesTheirWarningsAndErrorsButNotTheirNotes() {
        assertEquals(listOf(6L, 5L, 4L), shown(problemsOnly = true).map { it.id })
        assertEquals(listOf(5L), shown(problemsOnly = true, own = false).map { it.id })
    }

    @Test
    fun theDaysToShowCutThemAsTheyCutTheRest() {
        val later = start + 3 * 24 * 60 * 60 * 1000L
        val recent = events + Event(7, later, null, null, EventKind.OWN_NOTE, "A check of 3 apps started.")
        assertEquals(listOf(7L), within(recent, 1, later + 1).map { it.id })
        assertEquals(recent, within(recent, null, later + 1))
    }

    @Test
    fun anEntrySaysItsFirstLineAndHoldsTheRestUnderIt() {
        val error = events.last().message
        assertEquals("Engine task failed", ownHeadline(error))
        assertEquals("java.io.IOException: timeout\nat io.github.munzzyy.tern.A.b(A.kt:3)", ownDetail(error))
        assertEquals("Could not switch Obtainium links: denied", ownHeadline(events[3].message))
        assertNull(ownDetail(events[3].message))
    }

    @Test
    fun theSharedLogMarksThemAsTernsOwnToTheSecond() {
        assertEquals(
            listOf(
                "2023-11-14 22:13:25 · Error from Tern · Engine task failed",
                "  java.io.IOException: timeout",
                "  at io.github.munzzyy.tern.A.b(A.kt:3)",
                "2023-11-14 22:13 · Notes · GitHub did not answer",
                "2023-11-14 22:13:23 · Warning from Tern · Could not switch Obtainium links: denied",
                "2023-11-14 22:13 · Maps · Installed version 2.0 (version code 2)",
                "2023-11-14 22:13:21 · Note from Tern · A check of 3 apps started. It was asked for in Tern.",
                "2023-11-14 22:13 · Maps · Downloaded 8.4 MB",
            ).joinToString("\n"),
            activityText(events, zone, problemsOnly = false, own = true, marks = marks),
        )
    }

    @Test
    fun theSharedLogLeavesThemOutWhenTheyAreHidden() {
        val text = activityText(events, zone, problemsOnly = false, own = false, marks = marks)
        assertEquals(3, text.lines().size)
        assertTrue(text.lines().none { "from Tern" in it })
        assertEquals(
            listOf("2023-11-14 22:13:25 · Error from Tern · Engine task failed", "2023-11-14 22:13:23 · Warning from Tern · Could not switch Obtainium links: denied"),
            activityText(events, zone, problemsOnly = true, own = true, marks = marks).lines().filter { "from Tern" in it },
        )
    }
}
