package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.core.apk.ApkVerifier
import io.github.munzzyy.tern.core.apk.SignatureVerdict
import java.io.File

/** Tern's own check of a file's signature, the second reader next to Android. */
fun interface SignatureReader {
    fun read(file: File, sdk: Int): SignatureVerdict

    companion object {
        val OWN = SignatureReader { file, sdk -> ApkVerifier.verify(file, sdk, Downloader.MAX_BYTES) }
    }
}

enum class SignerFinding {
    ACCEPTED,

    /** Android and Tern do not come to the same signers for one file. */
    READ_DIFFERENTLY,

    /** Nobody has verified this part of a bundle. */
    PART_UNPROVEN,

    /** The part is signed, and not by the signers of the base. */
    PART_OTHER_SIGNER,
}

/** What two readings of one file's signature come to. Nothing here is accepted on a claim. */
object SignerJudge {
    /** Android verified the file and decides. Tern's own check can only speak against it. */
    fun readByAndroid(android: List<String>, own: SignatureVerdict): SignerFinding = when (own) {
        is SignatureVerdict.Holds -> if (own.certificates.toSet() == android.toSet()) SignerFinding.ACCEPTED else SignerFinding.READ_DIFFERENTLY
        is SignatureVerdict.DoesNotHold -> SignerFinding.READ_DIFFERENTLY
        is SignatureVerdict.CannotVerify -> SignerFinding.ACCEPTED
    }

    /** A part of a bundle that Android gave no reading of: Tern's own check is all there is. */
    fun notReadByAndroid(base: List<String>, own: SignatureVerdict): SignerFinding = when (own) {
        is SignatureVerdict.Holds -> if (own.certificates.isNotEmpty() && own.certificates.toSet() == base.toSet()) SignerFinding.ACCEPTED else SignerFinding.PART_OTHER_SIGNER
        is SignatureVerdict.DoesNotHold, is SignatureVerdict.CannotVerify -> SignerFinding.PART_UNPROVEN
    }
}
