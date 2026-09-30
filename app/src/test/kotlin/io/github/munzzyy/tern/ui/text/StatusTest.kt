package io.github.munzzyy.tern.ui.text

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.real.Checks
import io.github.munzzyy.tern.ui.testRow
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
    fun anAppPausedWhileStoresAreOffOffersNothingButTurningThemOn() {
        val paused = Checks.paused("paused")
        val row = testRow(status = paused.status, problem = paused.problem, offered = "2.0")
        assertEquals(StatusLabel.PAUSED, statusLabel(row))
        assertEquals(Tone.NEUTRAL, statusLabel(row).tone)
        assertTrue(isPaused(row))
        assertNull(primaryAction(row))
        assertNull(inlineAction(row))
        assertFalse(canPickInstall(row))
        assertFalse(canInstallNow(row))
        assertFalse(canUpdateNow(row))
        assertEquals(R.string.stores_advice, problemAdvice(ProblemKind.STORES_OFF))
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
        assertEquals(RowAction.CANCEL, inlineAction(row))
        assertFalse(canUpdateNow(row))
        assertFalse(canSkip(row))
        assertTrue(canSkip(row.copy(progress = null)))
    }

    @Test
    fun aDownloadIsStoppedFromItsRowUntilTheInstallerHasTheFile() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE)
        for (phase in listOf(Phase.QUEUED, Phase.DOWNLOADING, Phase.VERIFYING)) {
            assertEquals(phase.name, RowAction.CANCEL, inlineAction(row.copy(progress = Progress(phase))))
        }
        assertNull(inlineAction(row.copy(progress = Progress(Phase.INSTALLING))))
    }

    @Test
    fun networkFailuresReadCalmlyWhileOffline() {
        val row = testRow(status = AppStatus.ERROR, problem = Problem(ProblemKind.NETWORK, "no route"))
        assertEquals(StatusLabel.OFFLINE, statusLabel(row, online = false))
        assertEquals(Tone.NEUTRAL, StatusLabel.OFFLINE.tone)
        assertEquals(StatusLabel.ERROR, statusLabel(row, online = true))
        val blocked = testRow(status = AppStatus.BLOCKED, problem = Problem(ProblemKind.SIGNER_MISMATCH, "x"))
        assertEquals(StatusLabel.BLOCKED, statusLabel(blocked, online = false))
    }

    @Test
    fun waitingInstallOffersConfirmInTheRowAndOnTheDetail() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, progress = Progress(Phase.WAITING_FOR_USER))
        assertEquals(RowAction.CONFIRM, inlineAction(row))
        assertEquals(RowAction.CONFIRM, primaryAction(row))
        assertTrue(isWaitingForUser(row))
        assertFalse(isWaitingForUser(row.copy(progress = Progress(Phase.INSTALLING))))
    }

    @Test
    fun trackOnlyNeverInstallsAndIsMarkedAsSeenFromItsRow() {
        val row = testRow(status = AppStatus.NEW_RELEASE, trackOnly = true)
        assertEquals(RowAction.MARK_SEEN, primaryAction(row))
        assertEquals(RowAction.MARK_SEEN, inlineAction(row))
        assertTrue(isUpdate(row))
        assertFalse(canUpdateNow(row))
        assertFalse(canInstallNow(row.copy(status = AppStatus.NOT_INSTALLED, installed = null)))
        assertNull(inlineAction(row.copy(status = AppStatus.UP_TO_DATE)))
        assertNull(inlineAction(row.copy(status = AppStatus.NOT_INSTALLED, installed = null)))
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

    @Test
    fun anUpdateThatWaitsSaysWhetherAndroidWillAsk() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "1.0", offered = "1.1")
        assertEquals(PromptLine.SILENT, promptLine(row.copy(silentUpdate = true), sdk = 34))
        assertEquals(PromptLine.ASKS, promptLine(row.copy(silentUpdate = false), sdk = 34))
        assertNull(promptLine(row.copy(silentUpdate = null), sdk = 34))
    }

    @Test
    fun beforeAndroid12TheDeviceAlwaysAsks() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "1.0", offered = "1.1")
        for (silent in listOf(true, false, null)) {
            assertEquals(PromptLine.ALWAYS_ASKS, promptLine(row.copy(silentUpdate = silent), sdk = 30))
        }
        assertEquals(PromptLine.ALWAYS_ASKS, promptLine(row.copy(silentUpdate = false), sdk = 29))
        assertEquals(PromptLine.ASKS, promptLine(row.copy(silentUpdate = false), sdk = 31))
        assertEquals(R.string.explain_always_asks, PromptLine.ALWAYS_ASKS.explanation)
        assertEquals(R.string.explain_silent, PromptLine.SILENT.explanation)
        assertEquals(R.string.explain_silent, PromptLine.ASKS.explanation)
    }

    @Test
    fun nothingIsSaidAboutAPromptWhereNoUpdateCanStart() {
        val row = testRow(status = AppStatus.UPDATE_AVAILABLE, installed = "1.0", offered = "1.1").copy(silentUpdate = true)
        assertNull(promptLine(row.copy(status = AppStatus.UP_TO_DATE), sdk = 34))
        assertNull(promptLine(row.copy(status = AppStatus.BLOCKED), sdk = 30))
        assertNull(promptLine(row.copy(progress = Progress(Phase.DOWNLOADING, 1, 2)), sdk = 34))
        assertNull(promptLine(row.copy(config = row.config.copy(trackOnly = true)), sdk = 34))
        assertNull(promptLine(row.copy(installed = null), sdk = 30))
    }
}
