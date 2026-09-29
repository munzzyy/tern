package io.github.munzzyy.jackdaw.ui.text

import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.Progress
import io.github.munzzyy.jackdaw.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    @Test
    fun certainUpdateReadsUpdate() {
        assertEquals(StatusLabel.UPDATE, statusLabel(testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "1.0", offered = "1.1")))
    }

    @Test
    fun uncertainUpdateReadsLikely() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, certain = false)
        assertEquals(StatusLabel.UPDATE_LIKELY, statusLabel(row))
        assertEquals(Tone.ATTENTION, statusLabel(row).tone)
    }

    @Test
    fun progressWinsOverStatus() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, progress = Progress(Phase.WAITING_FOR_USER))
        assertEquals(StatusLabel.WAITING, statusLabel(row))
        assertEquals(StatusLabel.DOWNLOADING, statusLabel(row.copy(progress = Progress(Phase.DOWNLOADING, 1, 2))))
    }

    @Test
    fun rateLimitGetsItsOwnLabel() {
        val row = testRow(status = AppStatus.ERROR, problem = Problem(ProblemKind.RATE_LIMITED, "slow down", 5))
        assertEquals(StatusLabel.RATE_LIMITED, statusLabel(row))
        assertEquals(StatusLabel.ERROR, statusLabel(row.copy(problem = Problem(ProblemKind.NETWORK, "down"))))
        assertEquals(StatusLabel.INSTALL_FAILED, statusLabel(row.copy(problem = Problem(ProblemKind.STORAGE, "full"))))
    }

    @Test
    fun blockedAppNeverOffersInstall() {
        val row = testRow(status = AppStatus.BLOCKED, problem = Problem(ProblemKind.SIGNER_MISMATCH, "other signer"))
        assertEquals(RowAction.CHECK, primaryAction(row))
        assertNull(inlineAction(row))
        assertFalse(canPickInstall(row))
        assertTrue(canPickInstall(row.copy(status = AppStatus.UPDATE_AVAILABLE)))
    }

    @Test
    fun busyAppOffersCancelOnly() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, progress = Progress(Phase.DOWNLOADING, 1, 10))
        assertEquals(RowAction.CANCEL, primaryAction(row))
        assertNull(inlineAction(row))
        assertFalse(canUpdateNow(row))
        assertFalse(canSkip(row))
        assertTrue(canSkip(row.copy(progress = null)))
    }

    @Test
    fun trackOnlyNeverInstalls() {
        val row = testRow(status = AppStatus.NEW_RELEASE, trackOnly = true)
        assertEquals(RowAction.MARK_SEEN, primaryAction(row))
        assertNull(inlineAction(row))
        assertTrue(isUpdate(row))
    }

    @Test
    fun notInstalledWithoutFileCannotInstall() {
        val row = testRow(status = AppStatus.NOT_INSTALLED, installed = null, withFile = false)
        assertNull(inlineAction(row))
        assertNotEquals(RowAction.INSTALL, primaryAction(row))
        assertEquals(RowAction.INSTALL, inlineAction(row.copy(file = testRow().file)))
    }

    @Test
    fun upToDateInstalledOpens() {
        assertEquals(RowAction.OPEN, primaryAction(testRow()))
    }

    @Test
    fun everyProblemKindHasAdvice() {
        val advice = ProblemKind.entries.map(::problemAdvice)
        assertEquals(ProblemKind.entries.size, advice.toSet().size)
    }

    @Test
    fun aSignerMismatchOnAnInstalledAppDoesNotPointAtThePin() {
        assertEquals(R.string.advice_signer_mismatch_installed, problemAdvice(ProblemKind.SIGNER_MISMATCH, installed = true))
        assertEquals(R.string.advice_signer_broken, problemAdvice(ProblemKind.SIGNER_MISMATCH, installed = false))
        assertEquals(R.string.advice_signer_mismatch, problemAdvice(ProblemKind.PIN_MISMATCH, installed = true))
        assertEquals(R.string.advice_signer_mismatch, problemAdvice(ProblemKind.PIN_MISMATCH, installed = false))
        assertEquals(problemAdvice(ProblemKind.NETWORK, installed = true), problemAdvice(ProblemKind.NETWORK, installed = false))
    }
}
