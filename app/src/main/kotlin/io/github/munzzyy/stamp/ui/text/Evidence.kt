package io.github.munzzyy.stamp.ui.text

import androidx.annotation.StringRes
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.ChecksumState
import io.github.munzzyy.stamp.engine.SignerState
import io.github.munzzyy.stamp.engine.Verification

/** How reassuring a line of evidence is; drawn as an icon next to the words, never as colour alone. */
enum class Trust { GOOD, NOTE, BAD }

data class EvidenceLine(@param:StringRes val text: Int, val trust: Trust)

fun signerLine(v: Verification): EvidenceLine {
    val read = !v.signersVerified
    return when (v.signerState) {
        SignerState.UNKNOWN -> EvidenceLine(R.string.signer_unknown, Trust.NOTE)
        SignerState.FIRST_SEEN ->
            EvidenceLine(if (read) R.string.signer_first_seen_claimed else R.string.signer_first_seen, Trust.NOTE)
        SignerState.MATCHES_PIN ->
            EvidenceLine(if (read) R.string.signer_matches_pin_claimed else R.string.signer_matches_pin, Trust.GOOD)
        SignerState.MATCHES_INSTALLED ->
            EvidenceLine(if (read) R.string.signer_matches_installed_claimed else R.string.signer_matches_installed, Trust.GOOD)
        SignerState.MISMATCH ->
            EvidenceLine(if (read) R.string.signer_mismatch_claimed else R.string.signer_mismatch, Trust.BAD)
    }
}

fun checksumLine(v: Verification): EvidenceLine = when (v.checksum) {
    ChecksumState.NOT_PUBLISHED -> EvidenceLine(R.string.checksum_not_published, Trust.NOTE)
    ChecksumState.PENDING -> EvidenceLine(R.string.checksum_pending, Trust.NOTE)
    ChecksumState.MATCHED -> EvidenceLine(R.string.checksum_matched, Trust.GOOD)
    ChecksumState.MISMATCH -> EvidenceLine(R.string.checksum_mismatch, Trust.BAD)
}

/**
 * True once Android has read the downloaded file, its signer is the one Stamp or the device
 * already knows, no checksum speaks against it and nothing else stops it. The seal stands for
 * this and for nothing less: a first install has nothing to compare with, so it gets none.
 */
fun passedEveryCheck(row: AppRow): Boolean {
    val v = row.verification ?: return false
    if (row.status == AppStatus.BLOCKED || row.problem != null) return false
    val signer = v.signerState == SignerState.MATCHES_PIN || v.signerState == SignerState.MATCHES_INSTALLED
    val checksum = v.checksum == ChecksumState.MATCHED || v.checksum == ChecksumState.NOT_PUBLISHED
    return v.packageName != null && v.signersVerified && v.signers.isNotEmpty() && signer && checksum
}

/** [installed] decides for a signer nothing is known about yet: with the app on the device there is something to compare with. */
@StringRes
fun signerExplanation(v: Verification, installed: Boolean): Int = when (v.signerState) {
    SignerState.FIRST_SEEN -> R.string.explain_signer_first
    SignerState.UNKNOWN -> if (installed) R.string.explain_signer_installed else R.string.explain_signer_first
    SignerState.MATCHES_PIN, SignerState.MATCHES_INSTALLED, SignerState.MISMATCH -> R.string.explain_signer_installed
}

@StringRes
fun checksumExplanation(v: Verification): Int = when (v.checksum) {
    ChecksumState.NOT_PUBLISHED -> R.string.explain_checksum_none
    ChecksumState.PENDING -> R.string.explain_checksum_pending
    ChecksumState.MATCHED -> R.string.explain_checksum_matched
    ChecksumState.MISMATCH -> R.string.explain_checksum_mismatch
}

/** "ab12cd" becomes "AB:12:CD". Anything that is not even-length hex is returned unchanged. */
fun formatFingerprint(hex: String): String {
    val clean = hex.trim()
    if (clean.isEmpty() || clean.length % 2 != 0 || clean.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
        return clean
    }
    return clean.uppercase().chunked(2).joinToString(":")
}

/** The same text with a zero-width space after each colon, so a wrapped line never splits a pair, kept left to right. */
fun breakableFingerprint(formatted: String): String = ltr(formatted.replace(":", ":\u200B"))

private const val PERMISSION_PREFIX = "android.permission."

/** "android.permission.CAMERA" becomes "CAMERA"; other namespaces stay whole so nothing is hidden. */
fun shortPermission(name: String): String =
    if (name.startsWith(PERMISSION_PREFIX)) name.removePrefix(PERMISSION_PREFIX) else name

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** VirusTotal's page for a file fingerprint, or null when [sha256] is not exactly 64 lowercase hex digits. */
fun virusTotalUrl(sha256: String?): String? =
    sha256?.takeIf { SHA256_HEX.matches(it) }?.let { "https://www.virustotal.com/gui/file/$it" }
