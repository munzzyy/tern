package io.github.munzzyy.tern.ui.text

import androidx.annotation.StringRes
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ProblemKind

enum class Tone { NEUTRAL, ATTENTION, PROBLEM, BUSY }

enum class StatusLabel(@param:StringRes val text: Int, val tone: Tone) {
    UNKNOWN(R.string.status_unknown, Tone.NEUTRAL),
    UP_TO_DATE(R.string.status_up_to_date, Tone.NEUTRAL),
    UPDATE(R.string.status_update, Tone.ATTENTION),
    UPDATE_LIKELY(R.string.status_update_likely, Tone.ATTENTION),
    NOT_INSTALLED(R.string.status_not_installed, Tone.NEUTRAL),
    NEW_RELEASE(R.string.status_new_release, Tone.ATTENTION),
    BLOCKED(R.string.status_blocked, Tone.PROBLEM),
    ERROR(R.string.status_error, Tone.PROBLEM),
    RATE_LIMITED(R.string.status_rate_limited, Tone.PROBLEM),
    INSTALL_FAILED(R.string.status_install_failed, Tone.PROBLEM),
    OFFLINE(R.string.status_offline, Tone.NEUTRAL),
    CHECKING(R.string.status_checking, Tone.BUSY),
    QUEUED(R.string.status_queued, Tone.BUSY),
    DOWNLOADING(R.string.status_downloading, Tone.BUSY),
    VERIFYING(R.string.status_verifying, Tone.BUSY),
    INSTALLING(R.string.status_installing, Tone.BUSY),
    WAITING(R.string.status_waiting, Tone.BUSY),
}

/** While offline a network failure is the device's state, not the app's, so it reads calmly instead of as an error. */
fun quietOffline(row: AppRow, online: Boolean): Boolean = !online && row.problem?.kind == ProblemKind.NETWORK

fun statusLabel(row: AppRow, online: Boolean = true): StatusLabel {
    if (quietOffline(row, online) && row.progress == null && !row.checking) return StatusLabel.OFFLINE
    row.progress?.let {
        return when (it.phase) {
            Phase.QUEUED -> StatusLabel.QUEUED
            Phase.DOWNLOADING -> StatusLabel.DOWNLOADING
            Phase.VERIFYING -> StatusLabel.VERIFYING
            Phase.INSTALLING -> StatusLabel.INSTALLING
            Phase.WAITING_FOR_USER -> StatusLabel.WAITING
        }
    }
    if (row.checking) return StatusLabel.CHECKING
    return when (row.status) {
        AppStatus.UNKNOWN -> StatusLabel.UNKNOWN
        AppStatus.UP_TO_DATE -> StatusLabel.UP_TO_DATE
        AppStatus.UPDATE_AVAILABLE -> if (row.statusCertain) StatusLabel.UPDATE else StatusLabel.UPDATE_LIKELY
        AppStatus.NOT_INSTALLED -> StatusLabel.NOT_INSTALLED
        AppStatus.NEW_RELEASE -> StatusLabel.NEW_RELEASE
        AppStatus.BLOCKED -> StatusLabel.BLOCKED
        AppStatus.ERROR -> when (row.problem?.kind) {
            ProblemKind.RATE_LIMITED -> StatusLabel.RATE_LIMITED
            ProblemKind.INSTALL_FAILED, ProblemKind.STORAGE -> StatusLabel.INSTALL_FAILED
            else -> StatusLabel.ERROR
        }
    }
}

enum class RowAction(@param:StringRes val text: Int) {
    UPDATE(R.string.action_update),
    INSTALL(R.string.action_install),
    OPEN(R.string.action_open),
    MARK_SEEN(R.string.action_mark_seen),
    CHECK(R.string.action_check_again),
    CANCEL(R.string.action_cancel),
    CONFIRM(R.string.action_confirm),
}

fun isWaitingForUser(row: AppRow): Boolean = row.progress?.phase == Phase.WAITING_FOR_USER

fun isBusy(row: AppRow): Boolean = row.progress != null

/** What is said about the question Android may ask before an update, and what that means when the user asks. */
enum class PromptLine(@param:StringRes val text: Int, @param:StringRes val title: Int, @param:StringRes val explanation: Int) {
    SILENT(R.string.silent_yes, R.string.explain_silent_title, R.string.explain_silent),
    ASKS(R.string.silent_no, R.string.explain_silent_title, R.string.explain_silent),
    ALWAYS_ASKS(R.string.prompt_always_asks, R.string.explain_always_asks_title, R.string.explain_always_asks),
}

/** Android installs an update without asking from this version on, and only under its own conditions. */
const val FIRST_SILENT_SDK = 31

/** Null while no update waits to be started, and where nothing is known about how Android would take it. */
fun promptLine(row: AppRow, sdk: Int): PromptLine? {
    if (row.status != AppStatus.UPDATE_AVAILABLE || row.installed == null || row.config.trackOnly || isBusy(row)) return null
    if (sdk < FIRST_SILENT_SDK) return PromptLine.ALWAYS_ASKS
    return when (row.silentUpdate) {
        true -> PromptLine.SILENT
        false -> PromptLine.ASKS
        null -> null
    }
}

/** The one button that fits inline in a list row, if any. An app that is only tracked is never installed from there, only marked as seen. */
fun inlineAction(row: AppRow): RowAction? = when {
    isWaitingForUser(row) -> RowAction.CONFIRM
    isBusy(row) || row.checking -> null
    row.config.trackOnly -> RowAction.MARK_SEEN.takeIf { row.status == AppStatus.NEW_RELEASE && row.latest != null }
    row.status == AppStatus.UPDATE_AVAILABLE -> RowAction.UPDATE
    row.status == AppStatus.NOT_INSTALLED && row.file != null -> RowAction.INSTALL
    else -> null
}

/** The main button on the detail screen. Blocked apps never get an install button. */
fun primaryAction(row: AppRow): RowAction? = when {
    isWaitingForUser(row) -> RowAction.CONFIRM
    isBusy(row) -> RowAction.CANCEL
    row.checking -> null
    row.status == AppStatus.BLOCKED || row.status == AppStatus.ERROR -> RowAction.CHECK
    row.config.trackOnly && row.status == AppStatus.NEW_RELEASE -> RowAction.MARK_SEEN
    row.config.trackOnly -> RowAction.CHECK
    row.status == AppStatus.UPDATE_AVAILABLE -> RowAction.UPDATE
    row.status == AppStatus.NOT_INSTALLED && row.file != null -> RowAction.INSTALL
    row.status == AppStatus.UNKNOWN -> RowAction.CHECK
    row.installed != null -> RowAction.OPEN
    else -> RowAction.CHECK
}

fun canSkip(row: AppRow): Boolean =
    row.latest != null && !isBusy(row) && (row.status == AppStatus.UPDATE_AVAILABLE || row.status == AppStatus.NEW_RELEASE)

/** Installing a chosen version or file is offered only when nothing stops installs for this app. */
fun canPickInstall(row: AppRow): Boolean = !row.config.trackOnly && !isBusy(row) && row.status != AppStatus.BLOCKED

fun isUpdate(row: AppRow): Boolean = row.status == AppStatus.UPDATE_AVAILABLE || row.status == AppStatus.NEW_RELEASE

fun canUpdateNow(row: AppRow): Boolean =
    row.status == AppStatus.UPDATE_AVAILABLE && !row.config.trackOnly && !isBusy(row)

/** A first install that can start now: a file for this device is known and nothing stops it. It is checked like any other. */
fun canInstallNow(row: AppRow): Boolean =
    row.status == AppStatus.NOT_INSTALLED && row.file != null && !row.config.trackOnly && !isBusy(row)

@StringRes
/** [installed] matters for a signer mismatch: a pin can be changed, what Android refuses cannot. */
fun problemAdvice(kind: ProblemKind, installed: Boolean = false): Int = when (kind) {
    ProblemKind.NETWORK -> R.string.advice_network
    ProblemKind.RATE_LIMITED -> R.string.advice_rate_limited
    ProblemKind.NOT_FOUND -> R.string.advice_not_found
    ProblemKind.AUTH -> R.string.advice_auth
    ProblemKind.PARSE -> R.string.advice_parse
    ProblemKind.NO_RELEASES -> R.string.advice_no_releases
    ProblemKind.NO_FILE_FOR_DEVICE -> R.string.advice_no_file
    ProblemKind.CHECKSUM_MISMATCH -> R.string.advice_checksum_mismatch
    ProblemKind.SIGNER_MISMATCH -> if (installed) R.string.advice_signer_mismatch_installed else R.string.advice_signer_broken
    ProblemKind.PIN_MISMATCH -> R.string.advice_signer_mismatch
    ProblemKind.PACKAGE_MISMATCH -> R.string.advice_package_mismatch
    ProblemKind.DOWNGRADE -> R.string.advice_downgrade
    ProblemKind.INSTALL_FAILED -> R.string.advice_install_failed
    ProblemKind.STORAGE -> R.string.advice_storage
    ProblemKind.UNSUPPORTED -> R.string.advice_unsupported
}
