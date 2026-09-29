package io.github.munzzyy.stamp.core.apk

import java.io.File
import java.io.IOException

/** What [ApkVerifier] found out about the signature of one file, for one version of Android. */
sealed interface SignatureVerdict {
    /**
     * The signature was checked and holds. [scheme] is the one Android goes by (see [SignatureScheme]),
     * [certificates] the lowercase hex SHA-256 of each signer's certificate, and [lineage] the proof
     * of rotation with every link checked, oldest first, empty where the file carries none.
     */
    data class Holds(val scheme: Int, val certificates: List<String>, val lineage: List<String>) : SignatureVerdict

    /** The file is not signed the way it says. [reason] is for the log and is never shown as it is. */
    data class DoesNotHold(val reason: String) : SignatureVerdict

    /** Nothing was proven either way: the file needs a check that is not made here. */
    data class CannotVerify(val reason: String) : SignatureVerdict
}

/**
 * Checks the signature of a file on disk the way a device running Android [sdk] does: the newest
 * of the schemes v3.1, v3 and v2 that the device reads and the file carries decides, and a JAR
 * signature decides where there is none of them. A scheme that does not hold is never passed over
 * for an older one. Whatever cannot be checked ends in [SignatureVerdict.CannotVerify] or in
 * [SignatureVerdict.DoesNotHold], never in [SignatureVerdict.Holds].
 */
object ApkVerifier {
    fun verify(file: File, sdk: Int, maxBytes: Long): SignatureVerdict = try {
        FileSource(file).use { verify(file, it, sdk, maxBytes) }
    } catch (e: SignatureRefused) {
        SignatureVerdict.DoesNotHold(e.message.orEmpty())
    } catch (e: NotCheckedHere) {
        SignatureVerdict.CannotVerify(e.message.orEmpty())
    } catch (e: ApkFormatException) {
        SignatureVerdict.DoesNotHold("the file is malformed: ${e.message}")
    } catch (e: IOException) {
        SignatureVerdict.CannotVerify("the file could not be read: ${e.message}")
    } catch (e: RuntimeException) {
        SignatureVerdict.DoesNotHold("the check broke off: $e")
    }

    private fun verify(file: File, source: FileSource, sdk: Int, maxBytes: Long): SignatureVerdict {
        if (source.size > maxBytes) throw NotCheckedHere("the file has ${source.size} bytes, the limit is $maxBytes")
        val index = ZipIndex.open(source)
        if (index.isZip64) throw NotCheckedHere("ZIP64 files are not checked here")
        val block = ApkSigningBlock.locate(source, index.centralDirectoryOffset)
        if (block != null) {
            if (index.centralDirectoryOffset + index.centralDirectorySize != index.endRecordOffset) {
                throw SignatureRefused("the central directory does not end where the end record begins")
            }
            val layout = ApkLayout(block.offset, index.centralDirectoryOffset, index.endRecordOffset, source.size)
            SigningBlockVerifier(block, ContentDigests(source, layout), sdk).verify()?.let { return it }
        }
        return JarVerifier.verify(file, index, sdk, maxBytes)
    }
}
