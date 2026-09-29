package io.github.munzzyy.tern.core.icon

import io.github.munzzyy.tern.core.testing.Fixtures
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageProbeTest {
    private val whole = mapOf(
        "square.png" to ImageFacts(ImageKind.PNG, 96, 96),
        "wide.png" to ImageFacts(ImageKind.PNG, 200, 120),
        "edge.png" to ImageFacts(ImageKind.PNG, 2048, 2048),
        "huge.png" to ImageFacts(ImageKind.PNG, 5000, 5000),
        "square.jpg" to ImageFacts(ImageKind.JPEG, 96, 96),
        "progressive.jpg" to ImageFacts(ImageKind.JPEG, 96, 96),
        "lossy.webp" to ImageFacts(ImageKind.WEBP, 96, 96),
        "lossless.webp" to ImageFacts(ImageKind.WEBP, 96, 96),
        "alpha.webp" to ImageFacts(ImageKind.WEBP, 96, 96),
    )

    private fun fixture(name: String) = Fixtures.bytes("icons/$name")

    @Test
    fun aWholePictureIsNamedWithItsSize() {
        for ((name, facts) in whole) assertEquals(name, facts, ImageProbe.read(fixture(name)))
    }

    @Test
    fun aPictureThatStopsEarlyIsRefusedWhereverItStops() {
        for (name in whole.keys) {
            val bytes = fixture(name)
            for (length in 0 until bytes.size) assertNull("$name cut to $length of ${bytes.size}", ImageProbe.read(bytes.copyOf(length)))
        }
    }

    @Test
    fun whatIsNotOneOfTheThreeKindsIsRefused() {
        assertNull(ImageProbe.read(fixture("vector.svg")))
        assertNull(ImageProbe.read(fixture("picture.gif")))
        assertNull(ImageProbe.read("<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()))
        assertNull(ImageProbe.read(ByteArray(0)))
        assertNull(ImageProbe.read(ByteArray(4096)))
    }

    @Test
    fun bytesAfterTheEndOfThePictureAreLeftAlone() {
        for ((name, facts) in whole) assertEquals(name, facts, ImageProbe.read(fixture(name) + ByteArray(64) { 0x20 }))
    }

    @Test
    fun aDamagedPngIsRefused() {
        val bytes = fixture("square.png")
        val data = indexOf(bytes, "IDAT".toByteArray())
        val damaged = bytes.copyOf().also { it[data + 8] = (it[data + 8].toInt() xor 0x40).toByte() }
        assertNull(ImageProbe.read(damaged))

        val noData = bytes.copyOf().also { "iDAT".toByteArray().copyInto(it, data) }
        assertNull(ImageProbe.read(noData))
    }

    @Test
    fun aPngThatClaimsMoreThanItHoldsIsRefused() {
        val bytes = fixture("square.png").copyOf()
        val data = indexOf(bytes, "IDAT".toByteArray())
        byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).copyInto(bytes, data - 4)
        assertNull(ImageProbe.read(bytes))
    }

    @Test
    fun anEndMarkerInsideAnotherPartOfAJpegDoesNotEndIt() {
        val bytes = fixture("square.jpg")
        val hidden = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x06, 0xFF.toByte(), 0xD9.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val padded = bytes.copyOfRange(0, 2) + hidden + bytes.copyOfRange(2, bytes.size)
        assertEquals(ImageFacts(ImageKind.JPEG, 96, 96), ImageProbe.read(padded))
        assertNull(ImageProbe.read(padded.copyOf(padded.size - 2)))
        assertNull(ImageProbe.read(padded.copyOf(40)))
    }

    @Test
    fun aWebpThatClaimsMoreThanItHoldsIsRefused() {
        val bytes = fixture("lossy.webp").copyOf()
        bytes[4] = (bytes[4] + 2).toByte()
        assertNull(ImageProbe.read(bytes))
    }

    @Test
    fun damagedFilesAreAnsweredAndNeverThrow() {
        val random = Random(20260929)
        for (name in whole.keys) {
            val bytes = fixture(name)
            repeat(3000) {
                val damaged = bytes.copyOf()
                repeat(1 + random.nextInt(4)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
                val facts = ImageProbe.read(damaged)
                if (facts != null) assertEquals(name, whole.getValue(name).kind, facts.kind)
            }
        }
    }

    private fun indexOf(bytes: ByteArray, part: ByteArray): Int =
        (0..bytes.size - part.size).first { at -> part.indices.all { bytes[at + it] == part[it] } }
}
