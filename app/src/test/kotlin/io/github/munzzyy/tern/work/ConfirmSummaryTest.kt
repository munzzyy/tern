package io.github.munzzyy.tern.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmSummaryTest {
    @Test
    fun aNewConfirmationIsCountedWithThoseAlreadyShown() {
        assertEquals(1, Notifier.confirmSummary(emptySet(), summaryShown = false, posted = 11, gone = null))
        assertEquals(3, Notifier.confirmSummary(setOf(11, 12), summaryShown = true, posted = 13, gone = null))
        // Android may list the one just posted already, or not yet.
        assertEquals(2, Notifier.confirmSummary(setOf(11, 12), summaryShown = true, posted = 12, gone = null))
    }

    @Test
    fun theSummaryCountsDownAndGoesWithTheLastConfirmation() {
        assertEquals(1, Notifier.confirmSummary(setOf(11, 12), summaryShown = true, posted = null, gone = 11))
        assertEquals(0, Notifier.confirmSummary(setOf(11), summaryShown = true, posted = null, gone = 11))
        assertEquals(0, Notifier.confirmSummary(emptySet(), summaryShown = true, posted = null, gone = 11))
        assertEquals(0, Notifier.confirmSummary(emptySet(), summaryShown = false, posted = null, gone = 11))
    }

    @Test
    fun takingOneAwayNeverBringsBackASummaryThatIsNotShown() {
        assertNull(Notifier.confirmSummary(setOf(11, 12), summaryShown = false, posted = null, gone = 11))
    }
}
