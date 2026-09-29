package io.github.munzzyy.tern.core.apk

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/** Just enough DER to write a certificate that signs itself. */
internal object Der {
    val NULL = byteArrayOf(5, 0)

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
        }
        require(content.size < 0x10000)
        return byteArrayOf(tag.toByte()) + length + content
    }

    fun sequence(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { all, part -> all + part })

    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { all, part -> all + part })

    fun integer(value: Long) = tlv(0x02, BigInteger.valueOf(value).toByteArray())

    fun utf8(text: String) = tlv(0x0c, text.toByteArray())

    fun time(text: String) = tlv(0x17, text.toByteArray(Charsets.US_ASCII))

    fun bits(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

    fun version(number: Long) = tlv(0xa0, integer(number))

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = generateSequence(arc) { it ushr 7 }.takeWhile { it > 0 }.map { (it and 0x7f).toInt() }.toList().reversed()
            groups.forEachIndexed { i, group -> out.write(if (i < groups.lastIndex) group or 0x80 else group) }
        }
        return tlv(0x06, out.toByteArray())
    }
}

/** A key made for one test run, with a certificate that names nobody. */
internal class TestKey(val name: String, val pair: KeyPair) {
    val certificate: ByteArray = selfSigned()
    val sha256: String = Certificates.sha256(certificate)

    /** The identifier this key signs with when a test does not say. */
    val algorithm: Int
        get() = when (pair.public.algorithm) {
            "RSA" -> TestSigner.RSA_PKCS1_SHA256
            "EC" -> TestSigner.ECDSA_SHA256
            else -> TestSigner.DSA_SHA256
        }

    private fun selfSigned(): ByteArray {
        val (identifier, jca) = when (pair.public.algorithm) {
            "RSA" -> Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.NULL) to "SHA256withRSA"
            "EC" -> Der.sequence(Der.oid("1.2.840.10045.4.3.2")) to "SHA256withECDSA"
            else -> Der.sequence(Der.oid("2.16.840.1.101.3.4.3.2")) to "SHA256withDSA"
        }
        val subject = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("Example Test $name"))))
        val validity = Der.sequence(Der.time("200101000000Z"), Der.time("491231235959Z"))
        val body = Der.sequence(Der.version(2), Der.integer(1), identifier, subject, validity, subject, pair.public.encoded)
        val signature = Signature.getInstance(jca).apply {
            initSign(pair.private)
            update(body)
        }.sign()
        return Der.sequence(body, identifier, Der.bits(signature))
    }

    companion object {
        fun rsa(name: String, bits: Int = 2048) = TestKey(name, KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair())

        fun ec(name: String, curve: String = "secp256r1") =
            TestKey(name, KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair())

        fun dsa(name: String) = TestKey(name, KeyPairGenerator.getInstance("DSA").apply { initialize(2048) }.generateKeyPair())
    }
}

internal object TestKeys {
    val first by lazy { TestKey.rsa("first") }
    val second by lazy { TestKey.ec("second") }
    val third by lazy { TestKey.rsa("third") }
    val stranger by lazy { TestKey.rsa("stranger") }
    val curve384 by lazy { TestKey.ec("curve384", "secp384r1") }
    val dsa by lazy { TestKey.dsa("dsa") }
}

/** One signer of a block, with every field a test may want to set apart from the others. */
internal data class TestSignerSpec(
    val key: TestKey,
    val algorithms: List<Int> = listOf(key.algorithm),
    val digestAlgorithms: List<Int> = algorithms,
    val certificates: List<ByteArray> = listOf(key.certificate),
    val publicKey: ByteArray = key.pair.public.encoded,
    val minSdk: Int = 24,
    val maxSdk: Int = Int.MAX_VALUE,
    val signedMinSdk: Int = minSdk,
    val signedMaxSdk: Int = maxSdk,
    val attributes: List<Pair<Int, ByteArray>> = emptyList(),
    val signedBy: TestKey = key,
    /** Identifiers whose signature is written with one bit wrong. */
    val brokenSignatures: Set<Int> = emptySet(),
    /** Identifiers whose digest is written with one bit wrong. */
    val brokenDigests: Set<Int> = emptySet(),
)

/**
 * Signs a zip file the way the specification says, without sharing a line with the verifier, so
 * that a test can get one thing wrong and everything else right. What it writes was checked by
 * hand against apksigner.
 */
internal object TestSigner {
    const val RSA_PSS_SHA256 = 0x0101
    const val RSA_PSS_SHA512 = 0x0102
    const val RSA_PKCS1_SHA256 = 0x0103
    const val RSA_PKCS1_SHA512 = 0x0104
    const val ECDSA_SHA256 = 0x0201
    const val ECDSA_SHA512 = 0x0202
    const val DSA_SHA256 = 0x0301
    const val VERITY_RSA = 0x0421
    const val VERITY_ECDSA = 0x0423
    const val ML_DSA = 0x0501
    const val UNKNOWN = 0x0999

    const val V2 = 0x7109871a
    const val V3 = 0xf05368c0.toInt()
    const val V31 = 0x1b93ad61
    const val V32 = 0x70e1c89f

    const val ATTR_STRIPPING = 0xbeeff00d.toInt()
    const val ATTR_LINEAGE = 0x3ba06f8c
    const val ATTR_ROTATION_MIN_SDK = 0x559f8b02
    const val ATTR_DEV_RELEASE = 0xc2a6b3ba.toInt()
    const val ATTR_HYBRID_MIN_SDK = 0xbf940529.toInt()
    const val ATTR_HYBRID_MAX_SDK = 0x9f06b79c.toInt()

    private const val CHUNK = 1024 * 1024

    fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

    fun u64(value: Long): ByteArray = ByteArray(8) { (value ushr (8 * it)).toByte() }

    fun prefixed(bytes: ByteArray): ByteArray = u32(bytes.size) + bytes

    fun sequence(items: List<ByteArray>): ByteArray = items.fold(ByteArray(0)) { all, item -> all + prefixed(item) }

    fun attribute(id: Int, value: Int): Pair<Int, ByteArray> = id to u32(value)

    /** A zip with nothing signed in it: a compiled manifest of the fixtures and some other entries. */
    fun unsignedZip(extra: Int = 0): ByteArray {
        val signed = ZipIndex.open(BytesSource(SigningFixtures.bytes("v2-rsa-sha256.apk")))
        val manifest = signed.read(signed.find("AndroidManifest.xml")!!, ApkInspector.MAX_MANIFEST_BYTES)
        val entries = mutableListOf(ApkFixtures.deflated("AndroidManifest.xml", manifest), ApkFixtures.stored("assets/notes.txt", "notes".toByteArray()))
        if (extra > 0) entries += ApkFixtures.stored("assets/pad.bin", ByteArray(extra) { (it % 251).toByte() })
        return ApkFixtures.zip(*entries.toTypedArray())
    }

    fun sign(
        zip: ByteArray,
        v2: List<TestSignerSpec> = emptyList(),
        v3: List<TestSignerSpec> = emptyList(),
        v31: List<TestSignerSpec> = emptyList(),
        others: List<Pair<Int, ByteArray>> = emptyList(),
    ): ByteArray {
        val eocdAt = zip.size - 22
        require(zip.u32(eocdAt) == 0x06054b50L) { "the zip has a comment" }
        val directoryAt = zip.u32(eocdAt + 16).toInt()
        val sections = listOf(zip.copyOfRange(0, directoryAt), zip.copyOfRange(directoryAt, eocdAt), zip.copyOfRange(eocdAt, zip.size))
        val pairs = ArrayList<Pair<Int, ByteArray>>()
        if (v2.isNotEmpty()) pairs += V2 to prefixed(sequence(v2.map { signer(it, sections, withRange = false) }))
        if (v31.isNotEmpty()) pairs += V31 to prefixed(sequence(v31.map { signer(it, sections, withRange = true) }))
        if (v3.isNotEmpty()) pairs += V3 to prefixed(sequence(v3.map { signer(it, sections, withRange = true) }))
        pairs += others
        return withBlock(zip, directoryAt, block(pairs))
    }

    fun block(pairs: List<Pair<Int, ByteArray>>): ByteArray {
        val body = pairs.fold(ByteArray(0)) { all, (id, value) -> all + u64(value.size + 4L) + u32(id) + value }
        val size = u64(body.size + 24L)
        return size + body + size + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    }

    /** Puts [block] in front of the central directory of a zip that has none. */
    fun withBlock(zip: ByteArray, directoryAt: Int, block: ByteArray): ByteArray {
        val out = zip.copyOfRange(0, directoryAt) + block + zip.copyOfRange(directoryAt, zip.size)
        u32(directoryAt + block.size).copyInto(out, out.size - 22 + 16)
        return out
    }

    fun contentDigest(algorithm: String, sections: List<ByteArray>): ByteArray {
        val chunks = sections.flatMap { section -> (section.indices step CHUNK).map { section.copyOfRange(it, minOf(it + CHUNK, section.size)) } }
        val top = ByteArrayOutputStream()
        top.write(0x5a)
        top.write(u32(chunks.size))
        for (chunk in chunks) top.write(MessageDigest.getInstance(algorithm).digest(byteArrayOf(0xa5.toByte()) + u32(chunk.size) + chunk))
        return MessageDigest.getInstance(algorithm).digest(top.toByteArray())
    }

    private fun digestOf(id: Int, sections: List<ByteArray>): ByteArray = when (id) {
        RSA_PSS_SHA256, RSA_PKCS1_SHA256, ECDSA_SHA256, DSA_SHA256 -> contentDigest("SHA-256", sections)
        RSA_PSS_SHA512, RSA_PKCS1_SHA512, ECDSA_SHA512 -> contentDigest("SHA-512", sections)
        else -> ByteArray(40) { 7 }
    }

    /** A signature by [key] under the algorithm [id] names, or some other signature of the key where Java has none for it. */
    fun signature(id: Int, key: TestKey, data: ByteArray): ByteArray {
        val signer = when (id) {
            RSA_PSS_SHA256 -> Signature.getInstance("RSASSA-PSS").apply { setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)) }
            RSA_PSS_SHA512 -> Signature.getInstance("RSASSA-PSS").apply { setParameter(PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1)) }
            RSA_PKCS1_SHA512 -> Signature.getInstance("SHA512withRSA")
            ECDSA_SHA512 -> Signature.getInstance("SHA512withECDSA")
            else -> when (key.pair.public.algorithm) {
                "RSA" -> Signature.getInstance("SHA256withRSA")
                "EC" -> Signature.getInstance("SHA256withECDSA")
                else -> Signature.getInstance("SHA256withDSA")
            }
        }
        signer.initSign(key.pair.private)
        signer.update(data)
        return signer.sign()
    }

    private fun flipped(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }

    fun signer(spec: TestSignerSpec, sections: List<ByteArray>, withRange: Boolean): ByteArray {
        val digests = sequence(
            spec.digestAlgorithms.map { id ->
                val digest = digestOf(id, sections)
                u32(id) + prefixed(if (id in spec.brokenDigests) flipped(digest) else digest)
            },
        )
        val range = if (withRange) u32(spec.signedMinSdk) + u32(spec.signedMaxSdk) else ByteArray(0)
        val attributes = sequence(spec.attributes.map { (id, value) -> u32(id) + value })
        val signedData = prefixed(digests) + prefixed(sequence(spec.certificates)) + range + prefixed(attributes)
        val signatures = sequence(
            spec.algorithms.map { id ->
                val signature = signature(id, spec.signedBy, signedData)
                u32(id) + prefixed(if (id in spec.brokenSignatures) flipped(signature) else signature)
            },
        )
        val outside = if (withRange) u32(spec.minSdk) + u32(spec.maxSdk) else ByteArray(0)
        return prefixed(signedData) + outside + prefixed(signatures) + prefixed(spec.publicKey)
    }

    /**
     * The proof of rotation for [keys], oldest first. [wrongLink] names the key, by its place in the
     * list, whose signature by the key before it is written with one bit wrong.
     */
    fun lineage(keys: List<TestKey>, wrongLink: Int? = null, signedBy: Map<Int, TestKey> = emptyMap(), namedAlgorithm: Map<Int, Int> = emptyMap()): ByteArray {
        val levels = keys.mapIndexed { i, key ->
            val before = keys.getOrNull(i - 1)
            val signedWith = before?.algorithm ?: 0
            val signedData = prefixed(key.certificate) + u32(namedAlgorithm[i] ?: signedWith)
            val signature = if (before == null) ByteArray(0) else signature(signedWith, signedBy[i] ?: before, signedData)
            val next = if (i < keys.lastIndex) key.algorithm else 0
            prefixed(signedData) + u32(0x17) + u32(next) + prefixed(if (i == wrongLink) flipped(signature) else signature)
        }
        return u32(1) + sequence(levels)
    }
}
