package io.github.munzzyy.jackdaw.engine

import io.github.munzzyy.jackdaw.core.apk.ApkFormatException
import io.github.munzzyy.jackdaw.core.apk.BytesSource
import io.github.munzzyy.jackdaw.core.apk.ZipIndex
import io.github.munzzyy.jackdaw.install.ZipExtract
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class ZipExtractTest {
    private val dir: File = Files.createTempDirectory("zipextract").toFile().apply { deleteOnExit() }
    private val payload = Random(3).nextBytes(200_000) + ByteArray(100_000)

    private fun zip(stored: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val entry = ZipEntry("inner/app.apk")
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = payload.size.toLong()
                entry.crc = CRC32().apply { update(payload) }.value
            }
            z.putNextEntry(entry)
            z.write(payload)
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun extract(bytes: ByteArray, max: Long = Long.MAX_VALUE): File {
        val index = ZipIndex.open(BytesSource(bytes))
        val target = File(dir, "out-${System.nanoTime()}.apk")
        ZipExtract.extract(index, index.entries.single(), target, max)
        return target
    }

    @Test
    fun storedAndDeflatedEntriesComeOutByteForByte() {
        assertArrayEquals(payload, extract(zip(stored = true)).readBytes())
        assertArrayEquals(payload, extract(zip(stored = false)).readBytes())
    }

    @Test
    fun aCorruptedEntryIsRefusedAndLeavesNoFile() {
        for (stored in listOf(true, false)) {
            val bytes = zip(stored)
            val index = ZipIndex.open(BytesSource(bytes))
            val at = index.dataOffset(index.entries.single()).toInt() + 5_000
            bytes[at] = (bytes[at].toInt() xor 0x40).toByte()
            val target = File(dir, "corrupt-$stored.apk")
            try {
                ZipExtract.extract(ZipIndex.open(BytesSource(bytes)), index.entries.single(), target, Long.MAX_VALUE)
                fail("corrupted ${if (stored) "stored" else "deflated"} entry was extracted")
            } catch (_: ApkFormatException) {
                assertFalse(target.exists())
            }
        }
    }

    @Test
    fun anEntryLargerThanTheCapIsRefusedBeforeWriting() {
        try {
            extract(zip(stored = false), max = 1000)
            fail("an entry over the cap was extracted")
        } catch (_: ApkFormatException) {
            assertFalse(dir.listFiles().orEmpty().any { it.length() > 1000 })
        }
    }
}
