package io.github.munzzyy.jackdaw.core.apk

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * A certificate the APK CLAIMS to be signed with. Nothing here checks a signature: the file could
 * name any certificate at all, and only the device's installer proves the claim after download.
 * Use it to spot a changed key early, never as proof of who built the file.
 *
 * [scheme] is 1, 2, 3 or 31 (v3.1). [sha256] is the lowercase hex SHA-256 of the DER certificate, the
 * value `apksigner verify --print-certs` shows. [lineage] lists the proof-of-rotation chain oldest
 * first, as claimed in a v3 or v3.1 block, and is empty otherwise. [minSdk] and [maxSdk] are the
 * platform versions a v3 or v3.1 signer applies to.
 */
data class SignerInfo(
    val scheme: Int,
    val sha256: String,
    val subject: String?,
    val lineage: List<String>,
    val minSdk: Int = 0,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    fun appliesTo(sdk: Int): Boolean = sdk in minSdk..maxSdk
}

object SignatureScheme {
    const val V1 = 1
    const val V2 = 2
    const val V3 = 3
    const val V31 = 31
}

internal object ApkSigningBlock {
    private const val MAGIC = "APK Sig Block 42"
    private const val ID_V2 = 0x7109871a
    private const val ID_V3 = 0xf05368c0.toInt()
    private const val ID_V31 = 0x1b93ad61
    private const val ATTR_LINEAGE = 0x3ba06f8c
    private const val MAX_BLOCK = 16 * 1024 * 1024
    private const val MAX_PAIRS = 1024
    private const val MAX_SIGNERS = 64
    private const val MAX_CERTS = 64
    private const val MAX_ATTRIBUTES = 256
    private const val MAX_LINEAGE = 256

    fun read(source: RandomAccessSource, centralDirectoryOffset: Long): List<SignerInfo>? {
        if (centralDirectoryOffset < 32) return null
        val footer = source.read(centralDirectoryOffset - 24, 24)
        if (String(footer, 8, 16, Charsets.US_ASCII) != MAGIC) return null
        val size = LeReader(footer).u64()
        if (size < 24 || size > MAX_BLOCK || size > centralDirectoryOffset - 8) throw ApkFormatException("APK Signing Block size $size is impossible")
        val block = source.read(centralDirectoryOffset - size - 8, (size + 8).toInt())
        if (LeReader(block).u64() != size) throw ApkFormatException("APK Signing Block size fields disagree")
        val pairs = LeReader(block, 8, block.size - 24)
        val values = HashMap<Int, LeReader>()
        var count = 0
        while (pairs.hasRemaining()) {
            if (++count > MAX_PAIRS) throw ApkFormatException("Too many APK Signing Block entries")
            val length = pairs.u64()
            if (length < 4 || length > pairs.remaining) throw ApkFormatException("APK Signing Block entry of $length bytes")
            val pair = pairs.slice(length.toInt())
            val id = pair.i32()
            if (id == ID_V2 || id == ID_V3 || id == ID_V31) {
                if (values.put(id, pair) != null) throw ApkFormatException("Signature scheme block appears twice")
            }
        }
        val signers = ArrayList<SignerInfo>()
        values[ID_V2]?.let { signers += signers(it, SignatureScheme.V2) }
        values[ID_V3]?.let { signers += signers(it, SignatureScheme.V3) }
        values[ID_V31]?.let { signers += signers(it, SignatureScheme.V31) }
        return signers
    }

    private fun signers(block: LeReader, scheme: Int): List<SignerInfo> {
        val list = block.lengthPrefixed()
        val out = ArrayList<SignerInfo>()
        while (list.hasRemaining()) {
            if (out.size >= MAX_SIGNERS) throw ApkFormatException("Too many signers")
            val signer = list.lengthPrefixed()
            val signedData = signer.lengthPrefixed()
            signedData.lengthPrefixed()
            val certificates = signedData.lengthPrefixed()
            val first = certificates.lengthPrefixedBytes()
            var more = 0
            while (certificates.hasRemaining()) {
                if (++more > MAX_CERTS) throw ApkFormatException("Too many certificates")
                certificates.lengthPrefixed()
            }
            if (scheme == SignatureScheme.V2) {
                out.add(Certificates.signer(scheme, first, emptyList()))
            } else {
                val minSdk = signedData.length()
                val maxSdk = signedData.length()
                out.add(Certificates.signer(scheme, first, lineage(signedData.lengthPrefixed())).copy(minSdk = minSdk, maxSdk = maxSdk))
            }
        }
        if (out.isEmpty()) throw ApkFormatException("Signature block without signers")
        return out
    }

    private fun lineage(attributes: LeReader): List<String> {
        var count = 0
        while (attributes.hasRemaining()) {
            if (++count > MAX_ATTRIBUTES) throw ApkFormatException("Too many signer attributes")
            val attribute = attributes.lengthPrefixed()
            if (attribute.i32() != ATTR_LINEAGE) continue
            attribute.skip(4)
            val out = ArrayList<String>()
            while (attribute.hasRemaining()) {
                if (out.size >= MAX_LINEAGE) throw ApkFormatException("Lineage too long")
                val node = attribute.lengthPrefixed()
                val signed = node.lengthPrefixed()
                out.add(Certificates.sha256(signed.lengthPrefixedBytes()))
            }
            return out
        }
        return emptyList()
    }
}

internal object JarSignature {
    private val BLOCK = Regex("""META-INF/[^/]+\.(RSA|DSA|EC)""", RegexOption.IGNORE_CASE)
    private const val MAX_FILES = 8
    private const val MAX_BYTES = 1024 * 1024

    fun signatureFiles(index: ZipIndex): List<ZipEntry> = index.entries.filter { BLOCK.matches(it.name) }

    fun signers(index: ZipIndex): List<SignerInfo> {
        val files = signatureFiles(index)
        if (files.size > MAX_FILES) throw ApkFormatException("${files.size} JAR signature files")
        return files.map { entry ->
            val certificates = try {
                CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(index.read(entry, MAX_BYTES)))
                    .filterIsInstance<X509Certificate>()
            } catch (e: CertificateException) {
                throw ApkFormatException("${entry.name} is not a PKCS#7 signature block", e)
            } catch (e: RuntimeException) {
                throw ApkFormatException("${entry.name} is not a PKCS#7 signature block", e)
            }
            val leaf = leaf(certificates) ?: throw ApkFormatException("${entry.name} holds no certificate")
            Certificates.signer(SignatureScheme.V1, leaf.encoded, emptyList())
        }.distinctBy { it.sha256 }
    }

    private fun leaf(certificates: List<X509Certificate>): X509Certificate? =
        certificates.firstOrNull { candidate ->
            certificates.none { other -> other !== candidate && other.issuerX500Principal == candidate.subjectX500Principal }
        } ?: certificates.firstOrNull()
}

internal object Certificates {
    fun sha256(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).toHex()

    fun signer(scheme: Int, der: ByteArray, lineage: List<String>): SignerInfo =
        SignerInfo(scheme, sha256(der), subject(der), lineage)

    private fun subject(der: ByteArray): String? = try {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        certificate?.subjectX500Principal?.getName(X500Principal.RFC2253)
    } catch (_: CertificateException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
