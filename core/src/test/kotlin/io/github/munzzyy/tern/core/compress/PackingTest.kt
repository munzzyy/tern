package io.github.munzzyy.tern.core.compress

import io.github.munzzyy.tern.core.testing.Fixtures
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackingTest {
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun theFirstBytesSayHowAFileIsPackedWhateverItsName() {
        assertEquals(Packing.BZIP2, Packing.of(Fixtures.bytes("compress/text.bz2")))
        assertEquals(Packing.XZ, Packing.of(Fixtures.bytes("compress/text-crc64.xz")))
        assertEquals(Packing.GZIP, Packing.of(gzip("x".toByteArray())))
        assertEquals(Packing.ZSTD, Packing.of(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0, 0)))
        assertEquals(Packing.NONE, Packing.of("PK\u0003\u0004".toByteArray()))
        assertEquals(Packing.NONE, Packing.of("BZh0".toByteArray()))
        assertEquals(Packing.NONE, Packing.of(ByteArray(0)))
    }

    @Test
    fun eachPackingOpensToWhatWasPacked() {
        val text = Samples.text(20_000, 11)
        assertArrayEquals(text, Packing.open(ByteArrayInputStream(gzip(text)), Packing.GZIP, Long.MAX_VALUE).readBytes())
        assertArrayEquals(text, Packing.open(ByteArrayInputStream(Fixtures.bytes("compress/text-crc32.xz")), Packing.XZ, Long.MAX_VALUE).readBytes())
        assertArrayEquals(text, Packing.open(ByteArrayInputStream(text), Packing.NONE, 10).readBytes())
    }

    @Test
    fun gzipIsHeldToTheSameLimitAsTheOthers() {
        val big = gzip(ByteArray(50_000))
        assertThrows(CompressedDataException::class.java) { Packing.open(ByteArrayInputStream(big), Packing.GZIP, 40_000).readBytes() }
        assertEquals(50_000, Packing.open(ByteArrayInputStream(big), Packing.GZIP, 50_000).readBytes().size)
    }

    @Test
    fun zstdIsNotOpened() {
        assertThrows(CompressedDataException::class.java) { Packing.open(ByteArrayInputStream(ByteArray(8)), Packing.ZSTD, 10) }
    }
}
