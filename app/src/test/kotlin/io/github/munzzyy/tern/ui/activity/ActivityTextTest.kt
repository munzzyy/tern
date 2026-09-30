package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun whatTheSharedLogCarriesIsScrubbedWhateverWroteIt() {
        val raw = listOf(
            Event(4, 1_700_000_000_000, "c", "Beta", EventKind.ADDED, "Added https://example.org/a.apk?token=abc123XYZ"),
            Event(5, 1_700_000_060_000, "c", "Beta", EventKind.CHECK_FAILED, "HTTP 401: Authorization: token ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"),
            Event(6, 1_700_000_120_000, "d", "Mail", EventKind.FAILED, "Failed to fetch https://u:hunter2@mail.example/x.apk (Cookie: sid=q9; theme=dark)"),
        )
        val shared = activityText(raw, ZoneOffset.UTC, problemsOnly = false)
        for (secret in listOf("abc123XYZ", "ghp_", "hunter2", "sid=q9", "theme=dark")) assertFalse(shared, secret in shared)
        assertTrue(shared, "Added https://example.org/a.apk" in shared)
        assertTrue(shared, "Failed to fetch https://mail.example/x.apk" in shared)
    }
}
