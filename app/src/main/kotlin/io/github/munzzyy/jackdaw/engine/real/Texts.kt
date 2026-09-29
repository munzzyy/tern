package io.github.munzzyy.jackdaw.engine.real

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import io.github.munzzyy.jackdaw.R
import java.util.Date

/** Every sentence the engine shows, from resources so it can be translated. */
class Texts(context: Context) {
    private val c = context.applicationContext

    private fun s(id: Int, vararg args: Any): String = c.getString(id, *args)

    private fun bytes(n: Long): String = Formatter.formatShortFileSize(c, n)

    fun cannotWrite() = s(R.string.engine_cannot_write)
    fun downloadFailed(detail: String?) = if (detail.isNullOrBlank()) s(R.string.engine_download_failed_plain) else s(R.string.engine_download_failed, detail)
    fun serverStatus(code: Int) = s(R.string.engine_server_status, code)
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
    fun packageMismatch(expected: String?, actual: String) = s(R.string.engine_package_mismatch, expected.orEmpty(), actual)
    fun signerMismatch() = s(R.string.engine_signer_mismatch)
    fun downgrade(installed: String?, offered: String?) = s(R.string.engine_downgrade, installed ?: "?", offered ?: "?")
    fun testOnly() = s(R.string.engine_test_only)
    fun needsNewerAndroid(minSdk: Int) = s(R.string.engine_needs_newer_android, minSdk)

    fun checkNetwork(detail: String?) = s(R.string.engine_check_network, detail.orEmpty().take(200))
    fun checkNotFound(detail: String?) = s(R.string.engine_check_not_found, detail.orEmpty().take(200))
    fun checkAuth(detail: String?) = s(R.string.engine_check_auth, detail.orEmpty().take(200))
    fun checkRateLimited(untilMs: Long?) = s(R.string.engine_check_rate_limited, time(untilMs ?: System.currentTimeMillis()))
    fun checkParse(detail: String?) = s(R.string.engine_check_parse, detail.orEmpty().take(200))
    fun checkUnsupported(detail: String?) = s(R.string.engine_check_unsupported, detail.orEmpty().take(200))
    fun checkNoReleases() = s(R.string.engine_check_no_releases)
    fun noReleasePasses() = s(R.string.engine_no_release_passes)
    fun onlyPrereleases() = s(R.string.engine_only_prereleases)
    fun patternProblem(detail: String?) = s(R.string.engine_pattern_problem, detail.orEmpty().take(200))
    fun offline() = s(R.string.engine_offline)
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

    fun eventAdded(from: String) = s(R.string.engine_event_added, from)
    fun eventRemoved() = s(R.string.engine_event_removed)
    fun eventImported() = s(R.string.engine_event_imported)
    fun eventUpdateFound(version: String) = s(R.string.engine_event_update_found, version)
    fun eventDownloaded(size: Long) = s(R.string.engine_event_downloaded, bytes(size))
    fun eventVerified(packageName: String, versionCode: Long, signer: String) = s(R.string.engine_event_verified, packageName, versionCode, signer)
    fun eventInstalled(version: String, versionCode: Long) = s(R.string.engine_event_installed, version, versionCode)

    fun checksumGitHub() = s(R.string.engine_checksum_github)
    fun checksumIndex() = s(R.string.engine_checksum_index)
    fun checksumSource() = s(R.string.engine_checksum_source)
    fun checksumFile(name: String) = s(R.string.engine_checksum_file, name.take(120))
    fun checksumNotes() = s(R.string.engine_checksum_notes)

    fun channelUpdates() = s(R.string.engine_channel_updates)
    fun channelInstalled() = s(R.string.engine_channel_installed)
    fun channelAttention() = s(R.string.engine_channel_attention)
    fun channelTransfers() = s(R.string.engine_channel_transfers)
    fun notifyUpdates(count: Int, onlyName: String?) = if (count == 1 && onlyName != null) s(R.string.engine_notify_update_one, onlyName) else s(R.string.engine_notify_updates, count)
    fun notifyInstalled(count: Int, onlyName: String?) = if (count == 1 && onlyName != null) s(R.string.engine_notify_installed_one, onlyName) else s(R.string.engine_notify_installed, count)
    fun notifyConfirm(name: String) = s(R.string.engine_notify_confirm, name)
    fun notifyFailures(count: Int) = s(R.string.engine_notify_failures, count)
    fun notifyDownloading(name: String) = s(R.string.engine_notify_downloading, name)

    private fun time(ms: Long): String = DateFormat.getTimeFormat(c).format(Date(ms))
}
