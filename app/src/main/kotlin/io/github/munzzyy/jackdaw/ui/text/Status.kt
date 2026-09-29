package io.github.munzzyy.jackdaw.ui.text

import androidx.annotation.StringRes
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.ProblemKind

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
    CHECKING(R.string.status_checking, Tone.BUSY),
    QUEUED(R.string.status_queued, Tone.BUSY),
    DOWNLOADING(R.string.status_downloading, Tone.BUSY),
    VERIFYING(R.string.status_verifying, Tone.BUSY),
    INSTALLING(R.string.status_installing, Tone.BUSY),
    WAITING(R.string.status_waiting, Tone.BUSY),
}

fun statusLabel(row: AppRow): StatusLabel {
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
}

fun isBusy(row: AppRow): Boolean = row.progress != null

/** The one button that fits inline in a list row, if any. */
fun inlineAction(row: AppRow): RowAction? = when {
    isBusy(row) || row.checking || row.config.trackOnly -> null
    row.status == AppStatus.UPDATE_AVAILABLE -> RowAction.UPDATE
    row.status == AppStatus.NOT_INSTALLED && row.file != null -> RowAction.INSTALL
    else -> null
}

/** The main button on the detail screen. Blocked apps never get an install button. */
fun primaryAction(row: AppRow): RowAction? = when {
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

fun isUpdate(row: AppRow): Boolean = row.status == AppStatus.UPDATE_AVAILABLE || row.status == AppStatus.NEW_RELEASE

fun canUpdateNow(row: AppRow): Boolean =
    row.status == AppStatus.UPDATE_AVAILABLE && !row.config.trackOnly && !isBusy(row)

@StringRes
fun problemAdvice(kind: ProblemKind): Int = when (kind) {
    ProblemKind.NETWORK -> R.string.advice_network
    ProblemKind.RATE_LIMITED -> R.string.advice_rate_limited
    ProblemKind.NOT_FOUND -> R.string.advice_not_found
    ProblemKind.AUTH -> R.string.advice_auth
    ProblemKind.PARSE -> R.string.advice_parse
    ProblemKind.NO_RELEASES -> R.string.advice_no_releases
    ProblemKind.NO_FILE_FOR_DEVICE -> R.string.advice_no_file
    ProblemKind.CHECKSUM_MISMATCH -> R.string.advice_checksum_mismatch
    ProblemKind.SIGNER_MISMATCH -> R.string.advice_signer_mismatch
    ProblemKind.PACKAGE_MISMATCH -> R.string.advice_package_mismatch
    ProblemKind.DOWNGRADE -> R.string.advice_downgrade
    ProblemKind.INSTALL_FAILED -> R.string.advice_install_failed
    ProblemKind.STORAGE -> R.string.advice_storage
    ProblemKind.UNSUPPORTED -> R.string.advice_unsupported
}
