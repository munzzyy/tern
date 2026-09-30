package io.github.munzzyy.tern.ui.activity

import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityRangeTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun event(id: Long, atMs: Long) = Event(id, atMs, "a", "App", EventKind.INSTALLED, "Installed 1.0")

    @Test
    fun theLogCanBeCutToItsLastDays() {
        val events = listOf(event(1, now - 1000), event(2, now - day - 1000), event(3, now - 6 * day), event(4, now - 30 * day))
        assertEquals(events, within(events, null, now))
        assertEquals(listOf(1L), within(events, 1, now).map { it.id })
        assertEquals(listOf(1L, 2L), within(events, 2, now).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), within(events, 7, now).map { it.id })
    }

    @Test
    fun theRangesAreObtainiums() {
        assertEquals(listOf(null, 1, 2, 3, 4, 5, 7), RANGES)
    }
}
