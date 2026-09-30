package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityTextTest {
    private val events = listOf(
        Event(1, 1_700_000_000_000, "a", "Maps", EventKind.INSTALLED, "Installed 2.0"),
        Event(2, 1_700_000_060_000, "b", "Notes", EventKind.CHECK_FAILED, "GitHub did not answer"),
        Event(3, 1_700_000_120_000, null, null, EventKind.IMPORTED, ""),
    )

    @Test
    fun theLogIsSharedNewestFirstOneLineAnEntry() {
        assertEquals(
            listOf(
                "2023-11-14 22:15 · imported",
                "2023-11-14 22:14 · Notes · GitHub did not answer",
                "2023-11-14 22:13 · Maps · Installed 2.0",
            ).joinToString("\n"),
            activityText(events, ZoneOffset.UTC, problemsOnly = false),
        )
    }

    @Test
    fun onlyTheProblemsAreSharedWhenOnlyTheyAreShown() {
        assertEquals("2023-11-14 22:14 · Notes · GitHub did not answer", activityText(events, ZoneOffset.UTC, problemsOnly = true))
    }
}
