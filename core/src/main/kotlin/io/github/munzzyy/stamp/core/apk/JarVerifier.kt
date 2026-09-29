package io.github.munzzyy.stamp.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.CRC32

/**
 * Verifies a JAR signature (scheme v1) with the JAR verifier of the runtime: every entry is read to
 * its end, and every entry outside META-INF has to be signed by the same certificates. It is only
 * asked where the file has no signing block that the device reads.
 *
 * A JAR signature covers what the entries hold and not the zip structure around them, so two zip
 * readers can find different files in a signed one. Each entry the JAR reader hands out is
 * therefore held against what [ZipIndex] reads of it: name, local header, size and checksum.
 */
internal object JarVerifier {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val EXEMPT = "META-INF/"
    private const val HEADER = "X-Android-APK-Signed"
    private const val SDK_V2 = 24
    private const val SDK_V3 = 28
    private const val SDK_NEEDS_V2 = 30
    private const val MAX_SIGNATURE_FILE_BYTES = 16 * 1024 * 1024
    private const val BUFFER = 64 * 1024
    private const val DIFFERENT_ENTRIES = "the JAR reader and the zip reader find different entries"
    private val SIGNATURE_FILE = Regex("""META-INF/[^/]+\.SF""", RegexOption.IGNORE_CASE)

    fun verify(file: File, index: ZipIndex, sdk: Int, maxBytes: Long): SignatureVerdict.Holds {
        if (JarSignature.signatureFiles(index).isEmpty()) throw SignatureRefused("the file carries no JAR signature and no signing block that Android $sdk reads")
        for (entry in index.entries) if (!entry.isDirectory) index.readableDataOffset(entry)
        val jar = try {
            JarFile(file, true)
        } catch (e: IOException) {
            throw NotCheckedHere("this runtime does not open the file as a JAR: ${e.message}")
        }
        jar.use { return Reading(it, index, maxBytes).verdict(sdk) }
    }

    private class Reading(private val jar: JarFile, index: ZipIndex, private var budget: Long) {
        private val listed = index.entries.associateBy { it.name }
        private val buffer = ByteArray(BUFFER)
        private val signatureFiles = HashMap<String, ByteArray>()
        private var signatureFileBytes = MAX_SIGNATURE_FILE_BYTES
        private var manifest: ByteArray? = null
        private var signers: List<String>? = null
        private var unsigned: String? = null

        fun verdict(sdk: Int): SignatureVerdict.Holds {
            val seen = HashSet<String>()
            for (entry in jar.entries()) {
                val ours = listed[entry.name] ?: throw SignatureRefused(DIFFERENT_ENTRIES)
                if (!seen.add(entry.name)) throw SignatureRefused(DIFFERENT_ENTRIES)
                if (!entry.isDirectory) read(entry, ours)
            }
            if (seen.size != listed.size) throw SignatureRefused(DIFFERENT_ENTRIES)
            val manifest = BinaryManifest.parse(manifest ?: throw SignatureRefused("the JAR has no $MANIFEST"))
            // A JDK calls every entry of a JAR signed with SHA-1 unsigned and gives no reason.
            val signers = signers ?: throw NotCheckedHere("this runtime takes none of the entries of the JAR as signed")
            if (unsigned != null) throw SignatureRefused("$unsigned of the JAR is not signed")
            for ((name, bytes) in signatureFiles) refuseStripped(name, bytes, sdk)
            val target = manifest.targetSdk ?: manifest.minSdk ?: 0
            if (sdk >= SDK_NEEDS_V2 && target >= SDK_NEEDS_V2) {
                throw SignatureRefused("a target of $target needs a signing block on Android $sdk, and the file has a JAR signature alone")
            }
            return SignatureVerdict.Holds(SignatureScheme.V1, signers.distinct(), emptyList())
        }

        private fun read(entry: JarEntry, ours: ZipEntry) {
            val isManifest = entry.name == MANIFEST
            val isSignatureFile = SIGNATURE_FILE.matches(entry.name)
            val room = if (isManifest) ApkInspector.MAX_MANIFEST_BYTES else signatureFileBytes
            val kept = if (isManifest || isSignatureFile) ByteArrayOutputStream() else null
            val checksum = CRC32()
            var length = 0L
            try {
                jar.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        budget -= n
                        if (budget < 0) throw NotCheckedHere("the entries of the JAR hold more than this check reads")
                        checksum.update(buffer, 0, n)
                        length += n
                        if (kept != null) {
                            if (kept.size() + n > room) throw ApkFormatException("${entry.name} and what else is kept of the JAR are larger than $room bytes")
                            kept.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: SecurityException) {
                throw SignatureRefused("the JAR signature does not hold: ${e.message}")
            } catch (e: ApkFormatException) {
                throw e
            } catch (e: IOException) {
                throw SignatureRefused("${entry.name} of the JAR cannot be read: ${e.message}")
            }
            if (length != ours.uncompressedSize || checksum.value != ours.crc32) throw SignatureRefused(DIFFERENT_ENTRIES)
            if (isManifest) manifest = kept?.toByteArray()
            if (isSignatureFile && kept != null) {
                signatureFileBytes -= kept.size()
                signatureFiles[entry.name] = kept.toByteArray()
            }
            if (entry.name.startsWith(EXEMPT)) return
            val by = signersOf(entry)
            val before = signers
            when {
                by.isEmpty() -> unsigned = entry.name
                before == null -> signers = by
                before.toSet() != by.toSet() -> throw SignatureRefused("${entry.name} of the JAR is signed by other certificates than the entries before it")
            }
        }

        private fun signersOf(entry: JarEntry): List<String> = entry.codeSigners.orEmpty().map { signer ->
            val own = signer.signerCertPath.certificates.firstOrNull() ?: throw SignatureRefused("${entry.name} of the JAR is signed without a certificate")
            Certificates.sha256(own.encoded)
        }
    }

    /** A signing block that was there when the JAR was signed is named in the signature file, which the JAR signature covers. */
    private fun refuseStripped(name: String, signatureFile: ByteArray, sdk: Int) {
        val header = try {
            Manifest(ByteArrayInputStream(signatureFile)).mainAttributes.getValue(HEADER)
        } catch (e: IOException) {
            throw SignatureRefused("$name of the JAR cannot be read: ${e.message}")
        } ?: return
        for (scheme in header.split(',').mapNotNull { it.trim().toIntOrNull() }) {
            if ((scheme == SignatureScheme.V2 && sdk >= SDK_V2) || (scheme == SignatureScheme.V3 && sdk >= SDK_V3)) {
                throw SignatureRefused("$name says the file was signed with scheme v$scheme, and no such signature is in it")
            }
        }
    }
}
