package io.github.munzzyy.stamp.engine.real

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.core.select.PickReason
import io.github.munzzyy.stamp.net.isProxySilent
import java.io.IOException
import java.util.Date

/** Every sentence the engine shows, from resources so it can be translated. */
class Texts(context: Context) : ImportTexts {
    private val c = context.applicationContext

    private fun s(id: Int, vararg args: Any): String = c.getString(id, *args)

    /** Names, addresses and hashes keep their own order inside a right-to-left sentence. */
    private fun ltr(value: String): String = "\u2066$value\u2069"

    private fun q(id: Int, count: Int): String = c.resources.getQuantityString(id, count, count)

    private fun bytes(n: Long): String = Formatter.formatShortFileSize(c, n)


    fun cannotWrite() = s(R.string.engine_cannot_write)
    fun proxySilent() = s(R.string.engine_proxy_silent)
    fun downloadFailed(e: IOException) = if (e.isProxySilent()) proxySilent() else downloadFailed(e.message)
    fun downloadFailed(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_download_failed_plain) else s(R.string.engine_download_failed, detail)
    override fun serverStatus(code: Int) = s(R.string.engine_server_status, code)
    fun encodedDownload(encoding: String) = s(R.string.engine_encoded_download, encoding.take(40))
    fun fileTooLarge() = s(R.string.engine_file_too_large)
    fun noSpace(needed: Long, free: Long) = s(R.string.engine_no_space, bytes(needed), bytes(free))
    fun downloadCut(got: Long, total: Long) = s(R.string.engine_download_cut, bytes(got), bytes(total))

    fun checksumMismatch() = s(R.string.engine_checksum_mismatch)
    fun tarUnsupported() = s(R.string.engine_tar_unsupported)
    fun notAnApk(detail: String?) = s(R.string.engine_not_an_apk, detail.orEmpty().take(200))
    fun archiveHasNoApk() = s(R.string.engine_archive_no_apk)
    fun archiveHasNoBase() = s(R.string.engine_archive_no_base)
    fun unreadableFile(detail: String?) = s(R.string.engine_unreadable_file, detail.orEmpty().take(200))
    fun noFileForDevice(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_no_file_for_device) else s(R.string.engine_no_file_for_device_detail, detail.take(200))
    fun androidRefusedFile() = s(R.string.engine_android_refused_file)
    fun readsDifferently() = s(R.string.engine_reads_differently)
    fun unsigned() = s(R.string.engine_unsigned)
    fun packageMismatch(expected: String?, actual: String) = s(R.string.engine_package_mismatch, ltr(expected.orEmpty()), ltr(actual))
    fun signerMismatch() = s(R.string.engine_signer_mismatch)
    fun pinMismatch() = s(R.string.engine_pin_mismatch)
    fun builtInPinMismatch() = s(R.string.engine_built_in_pin_mismatch)
    fun warnBuiltInPin() = s(R.string.engine_warn_built_in_pin)
    fun splitSignerMismatch() = s(R.string.engine_split_signer_mismatch)
    fun partNotSigned() = s(R.string.engine4_part_not_signed)
    fun downgrade(installed: String?, offered: String?) = s(R.string.engine_downgrade, installed ?: "?", offered ?: "?")
    fun testOnly() = s(R.string.engine_test_only)
    fun needsNewerAndroid(minSdk: Int) = s(R.string.engine_needs_newer_android, minSdk)

    fun checkNetwork(detail: String?) = s(R.string.engine_check_network, detail.orEmpty().take(200))
    fun checkNotFound(detail: String?) = s(R.string.engine_check_not_found, detail.orEmpty().take(200))
    fun checkAuth(detail: String?) = s(R.string.engine_check_auth, detail.orEmpty().take(200))
    override fun checkRateLimited(untilMs: Long?) = s(R.string.engine_check_rate_limited, time(untilMs ?: System.currentTimeMillis()))
    fun checkParse(detail: String?) = s(R.string.engine_check_parse, detail.orEmpty().take(200))
    fun checkUnsupported(detail: String?) = s(R.string.engine_check_unsupported, detail.orEmpty().take(200))
    fun checkNoReleases() = s(R.string.engine_check_no_releases)
    fun noReleasePasses() = s(R.string.engine_no_release_passes)
    fun onlyPrereleases() = s(R.string.engine_only_prereleases)
    fun onlyOtherPackage() = s(R.string.engine_only_other_package)
    fun patternProblem(detail: String?) = s(R.string.engine_pattern_problem, detail.orEmpty().take(200))
    fun offline() = s(R.string.engine_offline)
    fun moveGone() = s(R.string.engine_move_gone)
    fun moveNothing() = s(R.string.engine_move_nothing)
    fun moveNotASource() = s(R.string.engine_move_not_a_source)
    fun moveAlreadyTracked() = s(R.string.engine_move_already_tracked)
    fun moveUnknownApp() = s(R.string.engine_move_unknown_app)
    fun moveUnreadable() = s(R.string.engine_move_unreadable)
    fun moveOtherApp(expected: String, offered: String) = s(R.string.engine_move_other_app, expected.take(200), offered.take(200))
    fun moveOtherSigner() = s(R.string.engine_move_other_signer)
    fun eventMoved(from: String, to: String) = s(R.string.engine_event_moved, from.take(300), to.take(300))
    fun starsBadName() = s(R.string.engine_stars_bad_name)
    fun starsNoUser(user: String) = s(R.string.engine_stars_no_user, user.take(40))

    override fun importNotAnExport() = s(R.string.engine_import_not_an_export)
    override fun importEmpty() = s(R.string.engine_import_empty)
    override fun importLocalAddress() = s(R.string.engine_import_local_address)
    fun warnLocalAddress() = s(R.string.engine_warn_local_address)
    override fun importUnreadableExport(detail: String?) = s(R.string.engine_import_unreadable_export, detail.orEmpty().take(200))
    override fun importTooLarge(limitBytes: Int) = s(R.string.engine_import_too_large, limitBytes / (1024 * 1024))
    fun importFileGone() = s(R.string.engine_import_file_gone)
    override fun linkNotHttps() = s(R.string.engine_link_not_https)
    override fun linkNotAnAddress() = s(R.string.engine_link_not_an_address)
    override fun linkLeavesHttps() = s(R.string.engine_link_leaves_https)
    override fun linkNotFound() = s(R.string.engine_link_not_found)
    override fun linkRefused(status: Int) = s(R.string.engine_link_refused, status)
    override fun linkUnreachable(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_link_unreachable_plain) else s(R.string.engine_link_unreachable, detail.take(200))
    override fun linkTooSlow() = s(R.string.engine_link_too_slow)
    override fun linkNotAnExport() = s(R.string.engine_link_not_an_export)
    fun exportNoPlace() = s(R.string.engine_export_no_place)
    fun exportFailed(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_export_failed_plain) else s(R.string.engine_export_failed, detail.take(200))

    fun trackOnly() = s(R.string.engine_track_only)
    fun noRelease() = s(R.string.engine_no_release)

    fun installConflict() = s(R.string.engine_install_conflict)
    fun installStorage() = s(R.string.engine_install_storage)
    fun installIncompatible() = s(R.string.engine_install_incompatible)
    fun installBlocked() = s(R.string.engine_install_blocked)
    fun installCancelled() = s(R.string.engine_install_cancelled)
    fun installInvalid() = s(R.string.engine_install_invalid)
    fun installTimeout() = s(R.string.engine_install_timeout)
    fun installFailed(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_install_failed_plain) else s(R.string.engine_install_failed, detail.take(300))
    fun installDowngrade() = s(R.string.engine_install_downgrade)
    fun installVersionMismatch(expected: Long, actual: Long) = s(R.string.engine_install_version_mismatch, expected, actual)
    fun installNotFinished() = s(R.string.engine_install_not_finished)

    fun warnTracked() = s(R.string.engine_warn_tracked)
    fun warnSignedDifferently() = s(R.string.engine_warn_signed_differently)
    fun warnNoFile() = s(R.string.engine_warn_no_file)
    fun warnPrerelease() = s(R.string.engine_warn_prerelease)
    fun warnMoved(url: String) = s(R.string.engine_warn_moved, url)
    fun severalApps() = s(R.string.engine_several_apps)
    fun notASource() = s(R.string.engine_not_a_source)
    fun nothingToSearch() = s(R.string.engine_nothing_to_search)

    fun eventAdded(from: String) = s(R.string.engine_event_added, ltr(from))
    fun eventRemoved() = s(R.string.engine_event_removed)
    fun eventImported() = s(R.string.engine_event_imported)
    fun eventUpdateFound(version: String) = s(R.string.engine_event_update_found, version)
    fun eventDownloaded(size: Long) = s(R.string.engine_event_downloaded, bytes(size))
    fun eventVerified(packageName: String, versionCode: Long, signer: String) = s(R.string.engine_event_verified, ltr(packageName), versionCode, ltr(signer))
    fun eventInstalled(version: String, versionCode: Long) = s(R.string.engine_event_installed, version, versionCode)

    fun checksumGitHub() = s(R.string.engine_checksum_github)
    fun checksumIndex() = s(R.string.engine_checksum_index)
    fun checksumSource() = s(R.string.engine_checksum_source)
    fun checksumFile(name: String) = s(R.string.engine_checksum_file, ltr(name.take(120)))
    fun checksumNotes() = s(R.string.engine_checksum_notes)

    fun channelUpdates() = s(R.string.engine_channel_updates)
    fun channelInstalled() = s(R.string.engine_channel_installed)
    fun channelAttention() = s(R.string.engine_channel_attention)
    fun channelTransfers() = s(R.string.engine_channel_transfers)
    fun notifyUpdates(count: Int, onlyName: String?) = if (count == 1 && onlyName != null) s(R.string.engine_notify_update_one, onlyName) else q(R.plurals.engine_notify_updates_count, count)
    fun notifyInstalled(count: Int, onlyName: String?) = if (count == 1 && onlyName != null) s(R.string.engine_notify_installed_one, onlyName) else q(R.plurals.engine_notify_installed_count, count)
    fun notifyConfirm(name: String) = s(R.string.engine_notify_confirm, name)
    fun notifyConfirmPlain() = s(R.string.notify_confirm_plain)
    fun notifyFailures(count: Int) = q(R.plurals.engine_notify_failures_count, count)

    fun pickReason(reason: PickReason): String = when (reason.kind) {
        PickReason.Kind.ABI_MATCH -> s(R.string.engine_pick_abi_match, reason.detail.orEmpty())
        PickReason.Kind.ABI_MISMATCH -> s(R.string.engine_pick_abi_mismatch, reason.detail.orEmpty())
        PickReason.Kind.UNIVERSAL -> s(R.string.engine_pick_universal)
        PickReason.Kind.NO_ABI -> s(R.string.engine_pick_no_abi)
        PickReason.Kind.VARIANT_MATCH -> s(R.string.engine_pick_variant_match, reason.detail.orEmpty())
        PickReason.Kind.VARIANT_MISMATCH -> s(R.string.engine_pick_variant_mismatch, reason.detail.orEmpty())
        PickReason.Kind.DEBUG_BUILD -> s(R.string.engine_pick_debug)
        PickReason.Kind.TEST_BUILD -> s(R.string.engine_pick_test)
        PickReason.Kind.UNSIGNED -> s(R.string.engine_pick_unsigned)
    }
    fun notifyDownloading(name: String) = s(R.string.engine_notify_downloading, name)
    fun notifyDownloadingPlain() = s(R.string.notify_downloading_plain)

    private fun time(ms: Long): String = DateFormat.getTimeFormat(c).format(Date(ms))
}
