package io.github.munzzyy.tern.core.compress

import io.github.munzzyy.tern.core.apk.TarReader
import io.github.munzzyy.tern.core.testing.Fixtures
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XzInputStreamTest {
    private fun unpack(bytes: ByteArray, maxOutput: Long = Long.MAX_VALUE, maxDictionary: Int = XzInputStream.MAX_DICTIONARY, chunk: Int = 8192): ByteArray =
        Samples.readAll(XzInputStream(ByteArrayInputStream(bytes), maxOutput, maxDictionary), chunk)

    private fun fixture(name: String) = Fixtures.bytes("compress/$name")

    @Test
    fun aShortTextComesOutAsItWent() {
        assertEquals("Hello, Tern.\n", String(unpack(fixture("hello.txt.xz"))))
    }

    @Test
    fun nothingPackedIsNothingUnpacked() {
        assertEquals(0, unpack(fixture("empty.xz")).size)
    }

    @Test
    fun aLongTextIsReadWhole() {
        assertArrayEquals(Samples.text(250_000, 7), unpack(fixture("text-crc64.xz")))
    }

    @Test
    fun everyKindOfCheckIsHeldTo() {
        val expected = Samples.text(20_000, 11)
        for (name in listOf("text-crc32.xz", "text-sha256.xz", "text-none.xz")) {
            assertArrayEquals(name, expected, unpack(fixture(name)))
        }
        // The check is the last thing of the block before the index; a change there is caught by it alone.
        for (name in listOf("text-crc32.xz", "text-sha256.xz")) {
            val bytes = fixture(name)
            val at = checkOffset(bytes)
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
            val error = assertThrows(name, CompressedDataException::class.java) { unpack(bytes) }
            assertTrue(error.message!!.contains("check"))
        }
    }

    @Test
    fun theLargestDictionaryAllowedIsRead() {
        // xz -9e names a dictionary of 64 MiB, the most allowed.
        assertArrayEquals(Samples.text(20_000, 11), unpack(fixture("text-extreme.xz")))
    }

    @Test
    fun aDictionaryLargerThanAllowedIsRefusedBeforeItIsMade() {
        val error = assertThrows(CompressedDataException::class.java) { unpack(fixture("text-crc64.xz"), maxDictionary = 1024 * 1024) }
        assertTrue(error.message!!.contains("1 MiB"))
    }

    @Test
    fun unusualLiteralAndPositionSettingsAreRead() {
        assertArrayEquals(Samples.text(20_000, 11), unpack(fixture("text-lclppb.xz")))
    }

    @Test
    fun dataThatDoesNotPackIsStoredAndReadAsItIs() {
        assertArrayEquals(Samples.noise(70_000, 3), unpack(fixture("noise.xz")))
    }

    @Test
    fun blocksThatNameTheirSizesAreHeldToThem() {
        assertArrayEquals(Samples.text(250_000, 7), unpack(fixture("blocks.xz")))
    }

    @Test
    fun streamsOneAfterTheOtherAreReadAsOne() {
        assertArrayEquals(Samples.text(3000, 1) + Samples.text(5000, 2), unpack(fixture("two-streams.xz")))
    }

    @Test
    fun theSameComesOutWhateverTheCallerReadsAtATime() {
        val expected = Samples.text(250_000, 7)
        assertArrayEquals(expected, unpack(fixture("blocks.xz"), chunk = 1))
        assertArrayEquals(expected, unpack(fixture("text-crc64.xz"), chunk = 1000))
    }

    @Test
    fun aTarArchiveInsideIsReadFileByFile() {
        val seen = LinkedHashMap<String, ByteArray>()
        XzInputStream(ByteArrayInputStream(fixture("apps.tar.xz"))).use { input ->
            TarReader.read(input) { entry, body -> seen[entry.name] = body.readBytes() }
        }
        assertEquals(listOf("app/app-arm64-v8a.apk", "app/notes.txt"), seen.keys.toList())
        assertArrayEquals(Samples.text(40_000, 21), seen.getValue("app/app-arm64-v8a.apk"))
    }

    @Test
    fun aFilterBesidesLzma2IsRefusedInWords() {
        val error = assertThrows(CompressedDataException::class.java) { unpack(fixture("x86.xz")) }
        assertTrue(error.message!!.contains("LZMA2"))
    }

    @Test
    fun moreThanTheLimitIsRefused() {
        assertThrows(CompressedDataException::class.java) { unpack(fixture("text-crc64.xz"), maxOutput = 100_000) }
        assertEquals(250_000, unpack(fixture("text-crc64.xz"), maxOutput = 250_000).size)
    }

    @Test
    fun dataCutShortIsRefused() {
        val whole = fixture("blocks.xz")
        for (length in listOf(5, 12, 20, 400, whole.size / 2, whole.size - 13, whole.size - 1)) {
            assertThrows("cut at $length", IOException::class.java) { unpack(whole.copyOf(length)) }
        }
    }

    @Test
    fun aChangedByteIsCaughtAndNeverLoopsOrOverruns() {
        for (name in listOf("text-crc64.xz", "text-none.xz", "noise.xz")) {
            val whole = fixture(name)
            for (at in listOf(0, 7, 9, 12, 13, 15, 40, 300, whole.size / 3, whole.size / 2, whole.size - 30, whole.size - 12, whole.size - 6, whole.size - 1)) {
                val damaged = whole.copyOf()
                damaged[at] = (damaged[at].toInt() xor 0x44).toByte()
                val outcome = runCatching { unpack(damaged) }
                // Without a check, damage inside the data may go unseen; it may never loop, overrun or pass the index.
                if (name == "text-none.xz" && outcome.isSuccess) continue
                assertTrue("$name at $at: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is IOException)
            }
        }
    }

    @Test
    fun dataThatIsNotXzIsRefused() {
        assertThrows(CompressedDataException::class.java) { unpack("plain text, long enough".toByteArray()) }
        assertThrows(CompressedDataException::class.java) { unpack(ByteArray(0)) }
    }

    @Test
    fun paddingAfterTheLastStreamIsAllowedAndAnythingElseIsNot() {
        val hello = fixture("hello.txt.xz")
        assertEquals("Hello, Tern.\n", String(unpack(hello + ByteArray(8))))
        assertThrows(IOException::class.java) { unpack(hello + ByteArray(3)) }
        assertThrows(IOException::class.java) { unpack(hello + "junk".toByteArray()) }
    }

    /** Where the check of the only block begins: it ends where the index begins, which the footer says how to find. */
    private fun checkOffset(bytes: ByteArray): Int {
        val backward = (bytes[bytes.size - 8].toInt() and 0xff) or ((bytes[bytes.size - 7].toInt() and 0xff) shl 8)
        val indexSize = (backward + 1) * 4
        return bytes.size - 12 - indexSize - 1
    }
}
