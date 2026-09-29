package io.github.munzzyy.stamp.core.apk

/** Where the parts of one signer lie in the file, as offsets from its start. */
internal class SignerMap(
    val signedData: IntRange,
    val firstDigest: IntRange,
    val firstCertificate: IntRange,
    val signedMinSdk: Int?,
    val attributes: IntRange,
    val minSdk: Int?,
    val firstSignature: IntRange,
    val publicKey: IntRange,
)

/** Finds the parts of a signed file by offset, so that a test can change one of them and nothing else. */
internal class ApkSurgery(val apk: ByteArray) {
    val eocdAt: Int = apk.size - 22
    val directoryAt: Int
    val blockAt: Int
    val pairs: List<Triple<Int, Int, Int>>

    init {
        require(apk.u32(eocdAt) == 0x06054b50L) { "the file has a zip comment" }
        directoryAt = apk.u32(eocdAt + 16).toInt()
        require(String(apk, directoryAt - 16, 16, Charsets.US_ASCII) == "APK Sig Block 42") { "the file has no signing block" }
        blockAt = directoryAt - apk.u32(directoryAt - 24).toInt() - 8
        val found = ArrayList<Triple<Int, Int, Int>>()
        var at = blockAt + 8
        while (at < directoryAt - 24) {
            val length = apk.u32(at).toInt()
            found += Triple(apk.u32(at + 8).toInt(), at + 12, at + 8 + length)
            at += 8 + length
        }
        pairs = found
    }

    fun value(id: Int): ByteArray = pairs.single { it.first == id }.let { apk.copyOfRange(it.second, it.third) }

    fun signers(id: Int): List<SignerMap> {
        val (_, from, to) = pairs.single { it.first == id }
        val out = ArrayList<SignerMap>()
        var at = from + 4
        while (at < to) {
            val length = apk.u32(at).toInt()
            out += signer(at + 4, withRange = id != TestSigner.V2)
            at += 4 + length
        }
        return out
    }

    private fun field(at: Int): IntRange = (at + 4) until (at + 4 + apk.u32(at).toInt())

    private fun signer(at: Int, withRange: Boolean): SignerMap {
        val signedData = field(at)
        val digests = field(signedData.first)
        val firstDigest = field(digests.first + 8)
        val certificates = field(digests.last + 1)
        val firstCertificate = field(certificates.first)
        var next = certificates.last + 1
        val signedMinSdk = if (withRange) next else null
        if (withRange) next += 8
        val attributes = field(next)
        next = signedData.last + 1
        val minSdk = if (withRange) next else null
        if (withRange) next += 8
        val signatures = field(next)
        val firstSignature = field(signatures.first + 8)
        return SignerMap(signedData, firstDigest, firstCertificate, signedMinSdk, attributes, minSdk, firstSignature, field(signatures.last + 1))
    }

    fun flip(at: Int, mask: Int = 1): ByteArray = apk.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    fun middle(range: IntRange): Int = range.first + (range.last - range.first) / 2

    /** The same entries, directory and end record around another signing block. */
    fun withBlock(block: ByteArray): ByteArray =
        TestSigner.withBlock(apk.copyOfRange(0, blockAt) + apk.copyOfRange(directoryAt, apk.size).also { TestSigner.u32(blockAt).copyInto(it, it.size - 22 + 16) }, blockAt, block)

    fun withPairs(keep: (Int) -> Boolean): ByteArray =
        withBlock(TestSigner.block(pairs.filter { keep(it.first) }.map { it.first to apk.copyOfRange(it.second, it.third) }))

    /** The file as it was before any signing block was put in. */
    fun withoutBlock(): ByteArray =
        apk.copyOfRange(0, blockAt) + apk.copyOfRange(directoryAt, apk.size).also { TestSigner.u32(blockAt).copyInto(it, it.size - 22 + 16) }

    fun block(): ByteArray = apk.copyOfRange(blockAt, directoryAt)
}
