package io.github.munzzyy.jackdaw.ui.text

import androidx.annotation.StringRes
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.SignerState
import io.github.munzzyy.jackdaw.engine.Verification

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

/** "ab12cd" becomes "AB:12:CD". Anything that is not even-length hex is returned unchanged. */
fun formatFingerprint(hex: String): String {
    val clean = hex.trim()
    if (clean.isEmpty() || clean.length % 2 != 0 || clean.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
        return clean
    }
    return clean.uppercase().chunked(2).joinToString(":")
}

/** The same text with a zero-width space after each colon, so a wrapped line never splits a pair. */
fun breakableFingerprint(formatted: String): String = formatted.replace(":", ":\u200B")

private const val PERMISSION_PREFIX = "android.permission."

/** "android.permission.CAMERA" becomes "CAMERA"; other namespaces stay whole so nothing is hidden. */
fun shortPermission(name: String): String =
    if (name.startsWith(PERMISSION_PREFIX)) name.removePrefix(PERMISSION_PREFIX) else name
