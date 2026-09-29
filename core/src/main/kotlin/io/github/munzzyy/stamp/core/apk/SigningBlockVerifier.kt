package io.github.munzzyy.stamp.core.apk

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Verifies the APK signature schemes v2, v3 and v3.1 for a device running Android [sdk], step by
 * step as the published specification has it. Of the signatures of a signer the strongest one
 * decides, and strength is ranked the way Android ranks it.
 */
internal class SigningBlockVerifier(private val block: SigningBlock, private val digests: ContentDigests, private val sdk: Int) {
    private class Signer(val certificate: ByteArray, val lineage: List<ByteArray>)

    private class Record(val id: Int, val value: ByteArray)

    private val factory = CertificateFactory.getInstance("X.509")

    /** The first version of Android that a v3.1 signer passed over was for; a v3 signer may name it. */
    private var rotationFrom: Int? = null

    /** Null when the block holds nothing a device of [sdk] reads, which leaves the JAR signature to decide. */
    fun verify(): SignatureVerdict.Holds? {
        if (sdk >= SDK_V32 && block.has(ApkSigningBlock.ID_V32)) throw NotCheckedHere("the file carries a v3.2 block, which Android $sdk goes by and which is not checked here")
        if (sdk >= SDK_V31) {
            val rotated = block.value(ApkSigningBlock.ID_V31)?.let { signerForDevice(it, SignatureScheme.V31) }
            if (rotated != null) return holds(SignatureScheme.V31, listOf(rotated))
        }
        if (sdk >= SDK_V3) {
            block.value(ApkSigningBlock.ID_V3)?.let { value ->
                val signer = signerForDevice(value, SignatureScheme.V3) ?: throw SignatureRefused("no signer of the v3 block is for Android $sdk")
                return holds(SignatureScheme.V3, listOf(signer))
            }
        }
        if (sdk >= SDK_V2) {
            block.value(ApkSigningBlock.ID_V2)?.let { return holds(SignatureScheme.V2, allSigners(it)) }
        }
        return null
    }

    private fun holds(scheme: Int, signers: List<Signer>) = SignatureVerdict.Holds(
        scheme,
        signers.map { Certificates.sha256(it.certificate) }.distinct(),
        signers.flatMap { it.lineage }.map(Certificates::sha256),
    )

    private fun allSigners(value: LeReader): List<Signer> {
        val signers = value.lengthPrefixed()
        val out = ArrayList<Signer>()
        while (signers.hasRemaining()) {
            if (out.size >= MAX_V2_SIGNERS) throw SignatureRefused("more than $MAX_V2_SIGNERS signers in the v2 block")
            val signer = signers.lengthPrefixed()
            out += signer(signer.lengthPrefixedBytes(), signer, SignatureScheme.V2, 0, 0)
        }
        if (out.isEmpty()) throw SignatureRefused("no signer in the v2 block")
        return out
    }

    private fun signerForDevice(value: LeReader, scheme: Int): Signer? {
        val signers = value.lengthPrefixed()
        var found: Signer? = null
        var count = 0
        while (signers.hasRemaining()) {
            if (++count > MAX_V3_SIGNERS) throw ApkFormatException("Too many signers")
            val signer = signers.lengthPrefixed()
            val signedData = signer.lengthPrefixedBytes()
            val minSdk = signer.i32()
            val maxSdk = signer.i32()
            if (sdk < minSdk || sdk > maxSdk) {
                if (scheme == SignatureScheme.V31) rotationFrom = minOf(rotationFrom ?: minSdk, minSdk)
                continue
            }
            if (found != null) throw SignatureRefused("more than one signer of the block is for Android $sdk")
            found = signer(signedData, signer, scheme, minSdk, maxSdk)
        }
        return found
    }

    /** [rest] is what follows the signed data in the signer: the signatures and the public key. */
    private fun signer(signedData: ByteArray, rest: LeReader, scheme: Int, minSdk: Int, maxSdk: Int): Signer {
        val signatures = records(rest.lengthPrefixed())
        val publicKey = rest.lengthPrefixedBytes()
        val algorithm = strongest(signatures)
        val signature = signatures.first { it.id == algorithm.id }
        if (!algorithm.verifies(algorithm.publicKey(publicKey), signedData, signature.value)) {
            throw SignatureRefused("signature ${algorithm.label} over the signed data does not verify")
        }

        val data = LeReader(signedData)
        val listed = records(data.lengthPrefixed())
        if (listed.map { it.id } != signatures.map { it.id }) throw SignatureRefused("digests and signatures name different algorithms")
        val certificates = certificates(data.lengthPrefixed())
        val own = certificates.firstOrNull() ?: throw SignatureRefused("no certificate in the signed data")
        if (!parse(own, "certificate 1").publicKey.encoded.contentEquals(publicKey)) throw SignatureRefused("the public key is not the key of the certificate")
        certificates.drop(1).forEachIndexed { i, der -> parse(der, "certificate ${i + 2}") }

        val lineage = if (scheme == SignatureScheme.V2) {
            v2Attributes(data.lengthPrefixed())
            emptyList()
        } else {
            if (data.i32() != minSdk || data.i32() != maxSdk) throw SignatureRefused("the platform range in the signed data is not the one outside it")
            v3Attributes(data.lengthPrefixed(), scheme, minSdk, own)
        }

        val signed = listed.last { it.id == algorithm.id }
        if (!MessageDigest.isEqual(signed.value, digests.of(algorithm.digest))) throw SignatureRefused("the content digest for ${algorithm.label} is not the digest of this file")
        return Signer(own, lineage)
    }

    /** The algorithm Android goes by: the first in the list among those with the strongest digest. */
    private fun strongest(signatures: List<Record>): SignatureAlgorithm {
        if (signatures.isEmpty()) throw SignatureRefused("no signature in the signer")
        val known = signatures.mapNotNull { SignatureAlgorithm.of(it.id) }
        var strongest = known.firstOrNull() ?: throw NotCheckedHere("no signature algorithm of the signer is known here: ${signatures.joinToString { "0x" + it.id.toString(16) }}")
        for (algorithm in known) if (algorithm.digest > strongest.digest) strongest = algorithm
        if (strongest.digest == ContentDigest.VERITY_SHA256) throw NotCheckedHere("Android goes by ${strongest.label}, which signs a verity digest")
        return strongest
    }

    private fun records(list: LeReader): List<Record> {
        val out = ArrayList<Record>()
        while (list.hasRemaining()) {
            if (out.size >= MAX_RECORDS) throw ApkFormatException("Too many signatures or digests")
            val record = list.lengthPrefixed()
            out += Record(record.i32(), record.lengthPrefixedBytes())
        }
        return out
    }

    private fun certificates(list: LeReader): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (list.hasRemaining()) {
            if (out.size >= MAX_CERTIFICATES) throw ApkFormatException("Too many certificates")
            out += list.lengthPrefixedBytes()
        }
        return out
    }

    private fun parse(der: ByteArray, what: String): X509Certificate = try {
        factory.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate ?: throw SignatureRefused("$what is no X.509 certificate")
    } catch (e: CertificateException) {
        throw SignatureRefused("$what cannot be read: ${e.message}")
    }

    private fun attributes(list: LeReader): List<Pair<Int, LeReader>> {
        val out = ArrayList<Pair<Int, LeReader>>()
        while (list.hasRemaining()) {
            if (out.size >= MAX_ATTRIBUTES) throw ApkFormatException("Too many signer attributes")
            val attribute = list.lengthPrefixed()
            out += attribute.i32() to attribute
        }
        return out
    }

    private fun v2Attributes(list: LeReader) {
        for ((id, value) in attributes(list)) {
            if (id == ATTR_STRIPPING && sdk >= SDK_V3 && value.i32() == SignatureScheme.V3) {
                throw SignatureRefused("the v2 signer says the file has a v3 signature, and it has none")
            }
        }
    }

    private fun v3Attributes(list: LeReader, scheme: Int, minSdk: Int, own: ByteArray): List<ByteArray> {
        var chain: List<ByteArray>? = null
        for ((id, value) in attributes(list)) {
            when (id) {
                ApkSigningBlock.ATTR_LINEAGE -> {
                    if (chain != null) throw SignatureRefused("two lineages in one signer")
                    val links = lineage(value)
                    if (links.isEmpty()) throw SignatureRefused("the lineage names no certificate")
                    if (!links.last().contentEquals(own)) throw SignatureRefused("the lineage does not end with the signer")
                    chain = links
                }
                ATTR_ROTATION_MIN_SDK -> if (sdk >= SDK_V31) {
                    val named = value.i32()
                    if (rotationFrom != named) throw SignatureRefused("the signer says a v3.1 block signs from Android $named on, the file has ${rotationFrom ?: "none"}")
                }
                ATTR_DEV_RELEASE -> if (scheme == SignatureScheme.V31 && sdk == minSdk) {
                    throw NotCheckedHere("the signer is for a preview of the Android after $sdk, and whether this device runs one is not known here")
                }
                ATTR_HYBRID_MIN_SDK, ATTR_HYBRID_MAX_SDK -> if (sdk >= SDK_V32) {
                    throw SignatureRefused("the signer says the file has a v3.2 block, and it has none")
                }
            }
        }
        return chain.orEmpty()
    }

    /** The certificates of the proof of rotation, oldest first, after each was found signed by the one before. */
    private fun lineage(value: LeReader): List<ByteArray> {
        value.skip(4)
        val out = ArrayList<ByteArray>()
        var before: X509Certificate? = null
        var announced = 0
        while (value.hasRemaining()) {
            if (out.size >= MAX_LINEAGE) throw ApkFormatException("Lineage too long")
            val place = out.size + 1
            val level = value.lengthPrefixed()
            val signedData = level.lengthPrefixedBytes()
            level.skip(4)
            val next = level.i32()
            val signature = level.lengthPrefixedBytes()
            if (before != null) {
                val algorithm = SignatureAlgorithm.of(announced) ?: throw NotCheckedHere("certificate $place of the lineage is signed with 0x${announced.toString(16)}, which is not known here")
                if (!algorithm.verifies(before.publicKey, signedData, signature)) {
                    throw SignatureRefused("certificate $place of the lineage is not signed by the one before it")
                }
            }
            val signed = LeReader(signedData)
            val der = signed.lengthPrefixedBytes()
            if (before != null && signed.i32() != announced) throw SignatureRefused("certificate $place of the lineage names another algorithm than the one before it announced")
            if (out.any { it.contentEquals(der) }) throw SignatureRefused("the lineage names a certificate twice")
            before = parse(der, "certificate $place of the lineage")
            out += der
            announced = next
        }
        return out
    }

    private companion object {
        const val SDK_V2 = 24
        const val SDK_V3 = 28
        const val SDK_V31 = 33
        const val SDK_V32 = 37
        const val ATTR_STRIPPING = 0xbeeff00d.toInt()
        const val ATTR_ROTATION_MIN_SDK = 0x559f8b02
        const val ATTR_DEV_RELEASE = 0xc2a6b3ba.toInt()
        const val ATTR_HYBRID_MIN_SDK = 0xbf940529.toInt()
        const val ATTR_HYBRID_MAX_SDK = 0x9f06b79c.toInt()
        const val MAX_V2_SIGNERS = 10
        const val MAX_V3_SIGNERS = 64
        const val MAX_RECORDS = 32
        const val MAX_CERTIFICATES = 64
        const val MAX_ATTRIBUTES = 256
        const val MAX_LINEAGE = 256
    }
}
