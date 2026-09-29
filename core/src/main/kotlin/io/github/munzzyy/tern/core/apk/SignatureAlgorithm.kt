package io.github.munzzyy.tern.core.apk

import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

/** The signature does not hold. The message goes to the log. */
internal class SignatureRefused(message: String) : Exception(message)

/** The file asks for a check that is not made here, so nothing is said about it. */
internal class NotCheckedHere(message: String) : Exception(message)

/** How the content of a file is digested, weakest first, in the order Android ranks them. */
internal enum class ContentDigest(val algorithm: String?) {
    CHUNKED_SHA256("SHA-256"),
    VERITY_SHA256(null),
    CHUNKED_SHA512("SHA-512"),
}

/**
 * A signature algorithm of the APK signature schemes v2 and v3, by the identifier the file names it
 * with. [names] are tried in order: Android calls RSASSA-PSS by another name than a desktop Java.
 */
internal class SignatureAlgorithm private constructor(
    val id: Int,
    private val keyAlgorithm: String,
    private val names: List<String>,
    private val parameters: AlgorithmParameterSpec?,
    val digest: ContentDigest,
) {
    val label: String get() = "0x" + id.toString(16).padStart(4, '0')

    /** Refuses a key that is none of the kind the algorithm signs with. */
    fun publicKey(encoded: ByteArray): PublicKey = try {
        KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(encoded))
    } catch (e: NoSuchAlgorithmException) {
        throw NotCheckedHere("this runtime reads no $keyAlgorithm key: ${e.message}")
    } catch (e: InvalidKeySpecException) {
        throw SignatureRefused("the public key is no $keyAlgorithm key: ${e.message}")
    }

    fun verifies(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        val verifier = instance()
        return try {
            verifier.initVerify(key)
            if (parameters != null) verifier.setParameter(parameters)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: InvalidKeyException) {
            false
        } catch (_: SignatureException) {
            false
        } catch (e: InvalidAlgorithmParameterException) {
            throw NotCheckedHere("this runtime does not take the parameters of $label: ${e.message}")
        }
    }

    private fun instance(): Signature {
        for (name in names) {
            try {
                return Signature.getInstance(name)
            } catch (_: NoSuchAlgorithmException) {
                continue
            }
        }
        throw NotCheckedHere("this runtime has no ${names.first()}, which $label needs")
    }

    companion object {
        private val PSS_SHA256 = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
        private val PSS_SHA512 = PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1)

        private val ALL = listOf(
            SignatureAlgorithm(0x0101, "RSA", listOf("SHA256withRSA/PSS", "RSASSA-PSS"), PSS_SHA256, ContentDigest.CHUNKED_SHA256),
            SignatureAlgorithm(0x0102, "RSA", listOf("SHA512withRSA/PSS", "RSASSA-PSS"), PSS_SHA512, ContentDigest.CHUNKED_SHA512),
            SignatureAlgorithm(0x0103, "RSA", listOf("SHA256withRSA"), null, ContentDigest.CHUNKED_SHA256),
            SignatureAlgorithm(0x0104, "RSA", listOf("SHA512withRSA"), null, ContentDigest.CHUNKED_SHA512),
            SignatureAlgorithm(0x0201, "EC", listOf("SHA256withECDSA"), null, ContentDigest.CHUNKED_SHA256),
            SignatureAlgorithm(0x0202, "EC", listOf("SHA512withECDSA"), null, ContentDigest.CHUNKED_SHA512),
            SignatureAlgorithm(0x0301, "DSA", listOf("SHA256withDSA"), null, ContentDigest.CHUNKED_SHA256),
            SignatureAlgorithm(0x0421, "RSA", listOf("SHA256withRSA"), null, ContentDigest.VERITY_SHA256),
            SignatureAlgorithm(0x0423, "EC", listOf("SHA256withECDSA"), null, ContentDigest.VERITY_SHA256),
            SignatureAlgorithm(0x0425, "DSA", listOf("SHA256withDSA"), null, ContentDigest.VERITY_SHA256),
        ).associateBy { it.id }

        /** Null for any other identifier. */
        fun of(id: Int): SignatureAlgorithm? = ALL[id]
    }
}
