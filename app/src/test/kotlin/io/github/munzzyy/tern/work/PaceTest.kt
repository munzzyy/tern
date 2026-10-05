package io.github.munzzyy.tern.work

import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {
    private fun downloading(done: Long) = Progress(Phase.DOWNLOADING, done, 1000)

    @Test
    fun reportsATenthOfASecondApartGoOutOncePerInterval() {
        val pace = Pace(250)
        val out = (0L..2000L step 100).filter { pace.due(it, changed = false) }
        assertEquals(listOf(0L, 300L, 600L, 900L, 1200L, 1500L, 1800L), out)
    }

    @Test
    fun theFirstReportAlwaysGoesOut() {
        assertTrue(Pace(1000).due(5_000, changed = false))
    }

    @Test
    fun aChangeBeyondTheBytesGoesOutAtOnceAndStartsTheIntervalAgain() {
        val pace = Pace(1000)
        assertTrue(pace.due(0, changed = false))
        assertFalse(pace.due(100, changed = false))
        assertTrue(pace.due(200, changed = true))
        assertFalse(pace.due(1100, changed = false))
        assertTrue(pace.due(1200, changed = false))
    }

    @Test
    fun aClockThatWentBackDoesNotHoldReportsBack() {
        val pace = Pace(1000)
        assertTrue(pace.due(10_000, changed = false))
        assertTrue(pace.due(2_000, changed = false))
        assertFalse(pace.due(2_500, changed = false))
        assertTrue(pace.due(3_000, changed = false))
    }

    @Test
    fun withNoIntervalEveryReportGoesOut() {
        val pace = Pace(0)
        assertTrue((0L..1000L step 100).all { pace.due(it, changed = false) })
    }

    @Test
    fun onlyTheBytesDoneMovingCanWait() {
        assertTrue(Pace.onlyBytes(downloading(10), downloading(20)))
        assertFalse(Pace.onlyBytes(downloading(10), downloading(10)))
        assertFalse(Pace.onlyBytes(downloading(10), Progress(Phase.VERIFYING)))
        assertFalse(Pace.onlyBytes(Progress(Phase.DOWNLOADING, 10, null), downloading(20)))
        assertFalse(Pace.onlyBytes(null, downloading(20)))
        assertFalse(Pace.onlyBytes(downloading(990), null))
    }

    @Test
    fun aTransferThatStartsOrEndsIsNeverHeldBack() {
        val one = mapOf("a" to downloading(10))
        assertTrue(Pace.onlyBytes(one, mapOf("a" to downloading(500))))
        assertFalse(Pace.onlyBytes(emptyMap(), one))
        assertFalse(Pace.onlyBytes(one, one + ("save:1" to downloading(0))))
        assertFalse(Pace.onlyBytes(one + ("save:1" to downloading(900)), mapOf("a" to downloading(20))))
        assertFalse(Pace.onlyBytes(one, mapOf("a" to Progress(Phase.VERIFYING))))
        assertFalse(Pace.onlyBytes(one, mapOf("b" to downloading(10))))
    }

    @Test
    fun aDownloadReportedTenTimesASecondIsDrawnFourTimesAndEveryPhaseShows() {
        val pace = Pace(250)
        var before: Progress? = null
        val drawn = ArrayList<Progress?>()
        fun set(at: Long, value: Progress?) {
            val changed = !Pace.onlyBytes(before, value)
            before = value
            if (pace.due(at, changed)) drawn += value
        }
        set(0, Progress(Phase.QUEUED))
        set(10, downloading(0))
        for (tick in 1..30) set(10 + tick * 100L, downloading(tick * 30L))
        set(3020, Progress(Phase.VERIFYING))
        set(3030, Progress(Phase.INSTALLING))
        set(3040, Progress(Phase.WAITING_FOR_USER))
        set(3050, null)
        val bytes = drawn.count { it?.phase == Phase.DOWNLOADING }
        assertTrue("$bytes byte reports drawn", bytes in 10..14)
        assertEquals(
            listOf(Phase.QUEUED, Phase.DOWNLOADING, Phase.VERIFYING, Phase.INSTALLING, Phase.WAITING_FOR_USER, null),
            drawn.map { it?.phase }.distinct(),
        )
        assertEquals(null, drawn.last())
    }
}
