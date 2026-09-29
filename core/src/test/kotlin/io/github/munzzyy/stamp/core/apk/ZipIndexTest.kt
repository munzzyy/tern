package io.github.munzzyy.stamp.core.apk

import io.github.munzzyy.stamp.core.apk.ApkFixtures.deflated
import io.github.munzzyy.stamp.core.apk.ApkFixtures.le
import io.github.munzzyy.stamp.core.apk.ApkFixtures.stored
import io.github.munzzyy.stamp.core.apk.ApkFixtures.zip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipIndexTest {
    private val text = "hello hello hello hello".toByteArray()
    private val zeros = ByteArray(1000)

    private fun open(bytes: ByteArray) = ZipIndex.open(BytesSource(bytes))

    private fun centralOffset(bytes: ByteArray, entry: Int = 0): Int {
        var at = open(bytes).centralDirectoryOffset.toInt()
        repeat(entry) { at += 46 + bytes.u16(at + 28) + bytes.u16(at + 30) + bytes.u16(at + 32) }
        return at
    }

    private fun patch(bytes: ByteArray, at: Int, value: Long, width: Int) = bytes.copyOf().also { le(value, width).copyInto(it, at) }

    @Test
    fun readsStoredAndDeflatedEntries() {
        val bytes = zip(stored("a.txt", text), deflated("dir/b.bin", zeros), comment = "a zip comment")
        val index = open(bytes)
        assertEquals(listOf("a.txt", "dir/b.bin"), index.entries.map { it.name })
        assertTrue(index.entries[0].isStored)
        assertEquals(ZipEntry.METHOD_DEFLATED, index.entries[1].method)
        assertArrayEquals(text, index.read(index.find("a.txt")!!, 1024))
        assertArrayEquals(zeros, index.read(index.find("dir/b.bin")!!, 1024))
    }

    @Test
    fun readsZip64Records() {
        val index = open(ApkFixtures.bytes("app-zip64.apk"))
        assertTrue(index.find("AndroidManifest.xml") != null)
        val plain = open(ApkFixtures.bytes("app-v1.apk"))
        assertEquals(index.find("AndroidManifest.xml")!!.crc32, plain.find("AndroidManifest.xml")!!.crc32)
    }

    @Test
    fun commentLengthMustMatch() {
        val bytes = zip(stored("a.txt", text), comment = "abc")
        assertThrows(ApkFormatException::class.java) { open(bytes + byteArrayOf(1)) }
        assertThrows(ApkFormatException::class.java) { open(bytes.copyOf(bytes.size - 1)) }
    }

    @Test
    fun inflatingPastTheDeclaredSizeIsABomb() {
        val bytes = zip(deflated("bomb", zeros))
        val cd = centralOffset(bytes)
        val shrunk = patch(bytes, cd + 24, 10, 4)
        val index = open(shrunk)
        assertThrows(ApkFormatException::class.java) { index.read(index.entries[0], 1 shl 20) }
        val honest = open(bytes)
        assertThrows(ApkFormatException::class.java) { honest.read(honest.entries[0], 999) }
    }

    @Test
    fun crcMismatchIsRefused() {
        val bytes = zip(deflated("a", text))
        val index = open(patch(bytes, centralOffset(bytes) + 16, 0x12345678, 4))
        assertThrows(ApkFormatException::class.java) { index.read(index.entries[0], 1024) }
    }

    @Test
    fun entriesReachingIntoTheCentralDirectoryAreRefused() {
        val bytes = zip(stored("a", text))
        val cd = centralOffset(bytes)
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, cd + 42, cd.toLong(), 4)) }
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, cd + 20, 5000, 4)) }
    }

    @Test
    fun localHeaderNameMustMatch() {
        val bytes = zip(stored("a", text))
        val index = open(patch(bytes, 30, 'b'.code.toLong(), 1))
        assertThrows(ApkFormatException::class.java) { index.read(index.entries[0], 1024) }
    }

    @Test
    fun duplicateNamesAreRefused() {
        val bytes = zip(stored("a", text), stored("b", text))
        val second = centralOffset(bytes, 1)
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, second + 46, 'a'.code.toLong(), 1)) }
    }

    @Test
    fun nonUtf8NamesAreRefused() {
        val bytes = zip(stored("a", text))
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, centralOffset(bytes) + 46, 0xff, 1)) }
    }

    @Test
    fun entryCountCapApplies() {
        val bytes = ApkFixtures.bytes("app-zip64.apk")
        val eocd64 = bytes.size - 22 - 20 - 56
        assertEquals(0x06064b50L, bytes.u32(eocd64))
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, eocd64 + 32, ZipIndex.MAX_ENTRIES + 1L, 8)) }
    }

    @Test
    fun zip64MarkersWithoutLocatorAreRefused() {
        val bytes = zip(stored("a", text))
        assertThrows(ApkFormatException::class.java) { open(patch(bytes, bytes.size - 22 + 16, 0xffffffffL, 4)) }
    }

    @Test
    fun tooSmallOrNotAZip() {
        assertThrows(ApkFormatException::class.java) { open(ByteArray(0)) }
        assertThrows(ApkFormatException::class.java) { open(ByteArray(4096)) }
    }
}
