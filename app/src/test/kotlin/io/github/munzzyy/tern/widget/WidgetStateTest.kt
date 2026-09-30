package io.github.munzzyy.tern.widget

import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStateTest {
    @Test
    fun theWidgetCountsUpdatesAndWhatCanStartNow() {
        val rows = listOf(
            testRow(id = "a", status = AppStatus.UPDATE_AVAILABLE, installed = "1.0", offered = "2.0", lastChecked = 100),
            testRow(id = "b", status = AppStatus.NEW_RELEASE, trackOnly = true, lastChecked = 300),
            testRow(id = "c", status = AppStatus.UP_TO_DATE, lastChecked = 200),
        )
        val state = WidgetState.of(rows, checking = false)
        assertEquals(2, state.updates)
        assertEquals(1, state.startable)
        assertEquals(300L, state.checkedAtMs)
    }

    @Test
    fun anEmptyListWasNeverChecked() {
        assertEquals(WidgetState(0, 0, null, true), WidgetState.of(emptyList(), checking = true))
    }
}
