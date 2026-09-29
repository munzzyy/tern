package io.github.munzzyy.tern.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files that carry a JAR signature and no signing block, or one that lost its block on the way. */
class ApkVerifierJarTest {
    private val old = SigningFixtures.bytes("v1-rsa-target29.apk")
    private val signer = SigningFixtures.certificatesAt("v1-rsa-target29.apk", 29)!!

    /** Writes the zip again, entry by entry. A JAR signature covers what the entries hold, so it stays valid. */
    private fun rewritten(zip: ByteArray, change: (String, ByteArray) -> ByteArray? = { _, bytes -> bytes }, added: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { target ->
            ZipInputStream(ByteArrayInputStream(zip)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val bytes = change(entry.name, source.readBytes()) ?: continue
                    target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    target.write(bytes)
                    target.closeEntry()
                }
            }
            for ((name, bytes) in added) {
                target.putNextEntry(java.util.zip.ZipEntry(name))
                target.write(bytes)
                target.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun names(zip: ByteArray) = ZipIndex.open(BytesSource(zip)).entries.map { it.name }

    /** Where the record of [name] starts in the central directory. */
    private fun centralRecord(zip: ByteArray, name: String): Int {
        var at = zip.u32(zip.size - 22 + 16).toInt()
        while (zip.u32(at) == 0x02014b50L) {
            val nameLength = zip.u16(at + 28)
            if (String(zip, at + 46, nameLength) == name) return at
            at += 46 + nameLength + zip.u16(at + 30) + zip.u16(at + 32)
        }
        error("no record of $name")
    }

    private fun flipped(zip: ByteArray, at: Int, mask: Int = 1) = zip.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    /**
     * A JAR signature covers what the entries hold. What the directory says about them is not signed,
     * and two zip readers can make two files of one. So the entries are held against the directory.
     */
    @Test
    fun aDirectoryThatSaysSomethingElseThanTheEntriesHold() {
        val index = ZipIndex.open(BytesSource(old))
        val deflated = centralRecord(old, "AndroidManifest.xml")
        val stored = centralRecord(old, "resources.arsc")
        assertEquals(8, old.u16(deflated + CENTRAL_METHOD))
        assertEquals(0, old.u16(stored + CENTRAL_METHOD))

        assertRefused("the checksum of a deflated entry", SigningFixtures.verify(flipped(old, deflated + CENTRAL_CRC), 29), "different entries")
        assertRefused("the checksum of a stored entry", SigningFixtures.verify(flipped(old, stored + CENTRAL_CRC), 29), "different entries")
        val longer = SigningFixtures.verify(flipped(old, deflated + CENTRAL_SIZE + 2), 29)
        assertTrue("the size of a deflated entry: $longer", longer is SignatureVerdict.DoesNotHold)
        assertRefused("the size of a stored entry", SigningFixtures.verify(flipped(old, stored + CENTRAL_SIZE + 2), 29), "two sizes")
        assertRefused("the method of a stored entry", SigningFixtures.verify(flipped(old, stored + CENTRAL_METHOD, 2), 29), "method")

        val header = index.find("resources.arsc")!!.localHeaderOffset.toInt()
        assertRefused("the name in the local header", SigningFixtures.verify(flipped(old, header + LOCAL_NAME + 3), 29), "Local header")
    }

    @Test
    fun aJarSignatureAloneHoldsForAnAppWithAnOldTarget() {
        assertTrue(names(old).any { it.startsWith("META-INF/") && it.endsWith(".RSA") })
        for (sdk in listOf(29, 30, 36)) assertHolds("on $sdk", SigningFixtures.verify(old, sdk), SignatureScheme.V1, signer)
        assertHolds("written again", SigningFixtures.verify(rewritten(old), 29), SignatureScheme.V1, signer)
    }

    @Test
    fun aJarSignatureAloneIsNotEnoughForATargetOfAndroid11() {
        val newer = SigningFixtures.bytes("v1-rsa-target30.apk")
        assertEquals(30, ApkInspector.inspect(BytesSource(newer)).manifest.targetSdk)
        assertHolds("Android 10 has no such rule", SigningFixtures.verify(newer, 29), SignatureScheme.V1, signer)
        for (sdk in listOf(30, 33, 36)) assertRefused("on $sdk", SigningFixtures.verify(newer, sdk), "target")
    }

    @Test
    fun oneBitInAnEntry() {
        val index = ZipIndex.open(BytesSource(old))
        val entries = index.entries.filter { !it.name.startsWith("META-INF/") && it.compressedSize > 0 }
        assertTrue(entries.any { it.isStored } && entries.any { !it.isStored })
        for (entry in entries) {
            // The last bits of a deflated entry are padding, and a JAR signature covers what comes out, so the middle it is.
            val at = (index.dataOffset(entry) + entry.compressedSize / 2).toInt()
            val changed = old.copyOf().also { it[at] = (it[at].toInt() xor 0x10).toByte() }
            val verdict = SigningFixtures.verify(changed, 29)
            assertTrue("${entry.name}: $verdict", verdict is SignatureVerdict.DoesNotHold)
        }
    }

    @Test
    fun anEntryWithOtherContentThanWasSigned() {
        val changed = rewritten(old, change = { name, bytes -> if (name == "resources.arsc") bytes + byteArrayOf(0) else bytes })
        assertTrue("the fixture has no resources.arsc", names(old).contains("resources.arsc"))
        assertRefused("a byte added", SigningFixtures.verify(changed, 29), "JAR")
    }

    @Test
    fun aNameOutOfTheFileCannotWriteLinesIntoTheLog() {
        val name = "a\nE/InstallGate: all is well\u202e" + "x".repeat(2000)
        val verdict = SigningFixtures.verify(rewritten(old, added = mapOf(name to "not signed".toByteArray())), 29)
        assertRefused("an entry with a line break in its name", verdict, "a E/InstallGate: all is well x")
        val reason = (verdict as SignatureVerdict.DoesNotHold).reason
        assertTrue(reason, reason.length <= 300 && reason.none { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test
    fun anEntryAddedAfterSigning() {
        val changed = rewritten(old, added = mapOf("classes.dex" to "not signed".toByteArray()))
        assertRefused("classes.dex", SigningFixtures.verify(changed, 29), "not signed")
        val hidden = rewritten(old, added = mapOf("META-INF/extra.txt" to "not signed".toByteArray()))
        assertHolds("Android leaves META-INF alone", SigningFixtures.verify(hidden, 29), SignatureScheme.V1, signer)
    }

    @Test
    fun anEntryThatOneOfTwoSignersNeverSigned() {
        val uneven = SigningFixtures.bytes("v1-signers-differ.apk")
        assertTrue(names(uneven).count { it.endsWith(".SF") } == 2)
        assertRefused("added by the second signer", SigningFixtures.verify(uneven, 29), "other certificates")
        val without = rewritten(uneven, change = { name, bytes -> if (name == "assets/late.txt") null else bytes })
        val verdict = SigningFixtures.verify(without, 29)
        assertTrue("without that entry both have signed everything: $verdict", verdict is SignatureVerdict.Holds && verdict.certificates.size == 2)
    }

    @Test
    fun anEntryThatTheEndRecordDoesNotCount() {
        val added = rewritten(old, added = mapOf("classes.dex" to "not signed".toByteArray()))
        val end = added.size - 22
        val count = added.u16(end + 10)
        assertEquals(names(old).size + 1, count)
        val hidden = added.copyOf().also {
            ApkFixtures.le(count - 1L, 2).copyInto(it, end + 8)
            ApkFixtures.le(count - 1L, 2).copyInto(it, end + 10)
        }
        assertFalse("ZipIndex goes by the count", names(hidden).contains("classes.dex"))
        assertRefused("the JAR reader of this Java walks the directory to its end", SigningFixtures.verify(hidden, 29), "different entries")
    }

    @Test
    fun signatureFilesAreKeptInMemoryUpToALimit() {
        val section = "Name: assets/some/entry/of/the/app.bin\r\nSHA-256-Digest: 47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\r\n\r\n"
        val large = ("Signature-Version: 1.0\r\n\r\n" + section.repeat(6 * 1024 * 1024 / section.length)).toByteArray()
        val two = rewritten(old, added = mapOf("META-INF/A.SF" to large, "META-INF/B.SF" to large))
        assertHolds("12 MiB of them", SigningFixtures.verify(two, 29), SignatureScheme.V1, signer)
        val three = rewritten(old, added = mapOf("META-INF/A.SF" to large, "META-INF/B.SF" to large, "META-INF/C.SF" to large))
        assertRefused("18 MiB of them", SigningFixtures.verify(three, 29), "larger than")
    }

    @Test
    fun aFileWithoutItsSignature() {
        val noBlock = rewritten(old, change = { name, bytes -> if (name.endsWith(".RSA")) null else bytes })
        assertRefused("no signature block file", SigningFixtures.verify(noBlock, 29), "no JAR signature")
        val nothing = rewritten(old, change = { name, bytes -> if (name.startsWith("META-INF/")) null else bytes })
        assertRefused("no META-INF", SigningFixtures.verify(nothing, 29), "no JAR signature")
        assertRefused("never signed", SigningFixtures.verify(TestSigner.unsignedZip(), 29), "no JAR signature")
    }

    @Test
    fun aSignatureBlockOfAnotherFile() {
        val other = SigningFixtures.bytes("v1-rsa-target30.apk")
        val block = ZipIndex.open(BytesSource(other)).let { index -> index.read(index.entries.first { it.name.endsWith(".RSA") }, 1 shl 20) }
        val changed = rewritten(old, change = { name, bytes -> if (name.endsWith(".RSA")) block else bytes })
        assertRefused("the block of another file", SigningFixtures.verify(changed, 29), "JAR signature does not hold")
        val damaged = rewritten(old, change = { name, bytes -> if (name.endsWith(".RSA")) flipped(bytes, bytes.size - 5) else bytes })
        assertRefused("one bit in the signature", SigningFixtures.verify(damaged, 29), "JAR signature does not hold")
    }

    /** Known and not mendable: the same key under another fingerprint. The pin is what refuses such an update. */
    @Test
    fun theCertificateThatComesWithAJarSignatureIsNotCoveredByIt() {
        val index = ZipIndex.open(BytesSource(old))
        val block = index.read(index.entries.first { it.name.endsWith(".RSA") }, 1 shl 20)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(block)).single().encoded
        assertEquals(signer, setOf(Certificates.sha256(certificate)))
        val start = (0..block.size - certificate.size).single { at -> certificate.indices.all { block[at + it] == certificate[it] } }
        val lastByteOfItsOwnSignature = start + certificate.size - 1
        val changed = rewritten(old, change = { name, bytes -> if (name.endsWith(".RSA")) flipped(bytes, lastByteOfItsOwnSignature) else bytes })
        val verdict = SigningFixtures.verify(changed, 29)
        assertTrue("$verdict", verdict is SignatureVerdict.Holds)
        assertEquals(1, (verdict as SignatureVerdict.Holds).certificates.size)
        assertFalse("$verdict", verdict.certificates.toSet() == signer)
    }

    @Test
    fun theListsBetweenTheSignatureAndTheEntriesChangedAfterSigning() {
        fun digest(bytes: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        val signatureFile = rewritten(old, change = { name, bytes -> if (name.endsWith(".SF")) String(bytes).replace("1.0 (Android)", "1.1 (Android)").toByteArray() else bytes })
        assertRefused("the signature file", SigningFixtures.verify(signatureFile, 29), "JAR signature does not hold")

        val index = ZipIndex.open(BytesSource(old))
        val resources = index.read(index.find("resources.arsc")!!, 1 shl 20)
        val other = resources + byteArrayOf(1)
        val listed = String(index.read(index.find("META-INF/MANIFEST.MF")!!, 1 shl 20))
        assertTrue(listed.contains(digest(resources)))
        val both = rewritten(
            old,
            change = { name, bytes ->
                when (name) {
                    "resources.arsc" -> other
                    "META-INF/MANIFEST.MF" -> listed.replace(digest(resources), digest(other)).toByteArray()
                    else -> bytes
                }
            },
        )
        assertRefused("an entry and its line in the manifest", SigningFixtures.verify(both, 29), "JAR signature does not hold")
    }

    @Test
    fun theSigningBlockTakenOutOfAFileThatSaysItHasOne() {
        val name = "v1v2v3-rsa-sha256.apk"
        val whole = SigningFixtures.bytes(name)
        val stripped = ApkSurgery(whole).withoutBlock()
        assertEquals(names(whole), names(stripped))
        assertRefused("on 29", SigningFixtures.verify(stripped, 29), "scheme")
        assertRefused("written again", SigningFixtures.verify(rewritten(whole), 29), "scheme")
        assertHolds("Android 6 knows no other scheme", SigningFixtures.verify(stripped, 23), SignatureScheme.V1, SigningFixtures.certificatesAt(name, 24)!!)
    }

    @Test
    fun aBlockWithNothingTheDeviceKnowsLeavesTheJarSignatureToDecide() {
        val padding = TestSigner.block(listOf(0x42726577 to ByteArray(64)))
        val withBlock = TestSigner.withBlock(old, old.u32(old.size - 22 + 16).toInt(), padding)
        assertHolds("an old app with a block of padding", SigningFixtures.verify(withBlock, 29), SignatureScheme.V1, signer)

        val whole = ApkSurgery(SigningFixtures.bytes("v1v2v3-rsa-sha256.apk"))
        assertRefused("v2 and v3 taken out, padding left", SigningFixtures.verify(whole.withPairs { it == 0x42726577 }, 29), "scheme")
    }

    private companion object {
        const val CENTRAL_METHOD = 10
        const val CENTRAL_CRC = 16
        const val CENTRAL_SIZE = 24
        const val LOCAL_NAME = 30
    }

    @Test
    fun aJarSignatureWithSha1GetsNoFalseAnswer() {
        val verdict = SigningFixtures.verify(ApkFixtures.bytes("app-jar-only.apk"), 29)
        val expected = ApkFixtures.expected.obj("app-jar-only.apk")!!.array("certificates")!!.strings().toSet()
        if (verdict is SignatureVerdict.Holds) assertHolds("SHA-1", verdict, SignatureScheme.V1, expected)
        assertFalse("$verdict", verdict is SignatureVerdict.DoesNotHold)
    }
}
