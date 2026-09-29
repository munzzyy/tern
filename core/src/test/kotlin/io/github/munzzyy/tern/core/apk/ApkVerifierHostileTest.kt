package io.github.munzzyy.tern.core.apk

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Signed files damaged at random. The verifier has to answer every one of them. Where it says that
 * a signing block holds, nothing outside the block may differ from what was signed. A JAR
 * signature covers less: what the entries outside META-INF hold, and neither the zip structure
 * around them nor the certificate that comes with the signature.
 */
class ApkVerifierHostileTest {
    private val seed = 0x7374616d7021L
    private val mutationsPerFixture = 1500

    private class Target(val name: String, val sdk: Int) {
        val bytes = SigningFixtures.bytes(name)
        val surgery = if (name.startsWith("v1-")) null else ApkSurgery(bytes)
        val certificates = SigningFixtures.certificatesAt(name, sdk)!!
        val signedContent = signedContent(bytes)
    }

    private companion object {
        fun signedContent(apk: ByteArray): Map<String, String> {
            val index = ZipIndex.open(BytesSource(apk))
            return index.entries.filter { !it.isDirectory && !it.name.startsWith("META-INF/") }.associate { it.name to Certificates.sha256(index.read(it, 1 shl 20)) }
        }
    }

    private val targets = listOf(
        Target("v2-rsa-sha256.apk", 29),
        Target("v3-rotated.apk", 29),
        Target("v2v3-ec-sha512.apk", 36),
        Target("v2v3-rotated.apk", 27),
        Target("v31-rotated-twice.apk", 36),
        Target("v1v2v3-rsa-sha256.apk", 30),
        Target("v1-rsa-target29.apk", 29),
    )

    private fun mutate(target: Target, random: Random): ByteArray {
        val bytes = target.bytes.copyOf()
        val directory = ZipIndex.open(BytesSource(target.bytes)).centralDirectoryOffset.toInt()
        val hotspots = listOf(0, bytes.size - 512, directory - 4096, directory - 2048)
        fun offset(width: Int): Int {
            val base = if (random.nextInt(3) == 0) 0 else hotspots[random.nextInt(hotspots.size)].coerceAtLeast(0)
            val span = if (base == 0) bytes.size else minOf(4096, bytes.size - base)
            return (base + random.nextInt(maxOf(1, span - width))).coerceIn(0, maxOf(0, bytes.size - width))
        }
        return when (random.nextInt(5)) {
            0 -> bytes.also { val at = offset(1); bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
            1 -> bytes.also { repeat(1 + random.nextInt(8)) { _ -> val at = offset(1); bytes[at] = (bytes[at].toInt() xor (1 + random.nextInt(255))).toByte() } }
            2 -> bytes.copyOf(random.nextInt(bytes.size))
            3 -> bytes.also { ApkFixtures.le(listOf(0L, 1L, 0x7fffffffL, 0xffffffffL)[random.nextInt(4)], 4).copyInto(bytes, offset(4)) }
            else -> bytes.also { ApkFixtures.le(listOf(0L, 0x7fffL, 0xffffL)[random.nextInt(3)], 2).copyInto(bytes, offset(2)) }
        }
    }

    @Test
    fun damagedFilesAreAnsweredAndNeverHoldForOtherContent() {
        var held = 0
        var refused = 0
        var unanswered = 0
        for (target in targets) {
            val random = Random(seed xor target.name.hashCode().toLong())
            repeat(mutationsPerFixture) { round ->
                val damaged = mutate(target, random)
                val label = "${target.name}, mutation $round"
                val verdict = try {
                    SigningFixtures.verify(damaged, target.sdk)
                } catch (e: Throwable) {
                    fail("$label threw ${e::class.java.name}: ${e.message}")
                    return
                }
                when (verdict) {
                    is SignatureVerdict.Holds -> {
                        held++
                        val surgery = target.surgery
                        if (surgery == null || verdict.scheme == SignatureScheme.V1) {
                            assertEquals(label, target.signedContent, signedContent(damaged))
                        } else {
                            assertEquals(label, target.certificates, verdict.certificates.toSet())
                            assertEquals(label, target.bytes.size, damaged.size)
                            assertArrayEquals(label, target.bytes.copyOfRange(0, surgery.blockAt), damaged.copyOfRange(0, surgery.blockAt))
                            assertArrayEquals(label, target.bytes.copyOfRange(surgery.directoryAt, damaged.size), damaged.copyOfRange(surgery.directoryAt, damaged.size))
                        }
                    }
                    is SignatureVerdict.DoesNotHold -> refused++
                    is SignatureVerdict.CannotVerify -> unanswered++
                }
            }
        }
        assertTrue("$held held, $refused refused, $unanswered without an answer", refused > targets.size * mutationsPerFixture / 2)
        assertTrue("no mutation fell into the padding of a signing block, so the test proves less than it says", held > 0)
    }
}
