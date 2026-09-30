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

class Bzip2InputStreamTest {
    private fun unpack(bytes: ByteArray, maxOutput: Long = Long.MAX_VALUE, chunk: Int = 8192): ByteArray =
        Samples.readAll(Bzip2InputStream(ByteArrayInputStream(bytes), maxOutput), chunk)

    private fun fixture(name: String) = Fixtures.bytes("compress/$name")

    @Test
    fun aShortTextComesOutAsItWent() {
        assertEquals("Hello, Tern.\n", String(unpack(fixture("hello.txt.bz2"))))
    }

    @Test
    fun nothingPackedIsNothingUnpacked() {
        assertEquals(0, unpack(fixture("empty.bz2")).size)
    }

    @Test
    fun everyBlockOfALongTextIsRead() {
        // Packed at level 1, so 250 000 bytes are three blocks.
        assertArrayEquals(Samples.text(250_000, 7), unpack(fixture("text.bz2")))
    }

    @Test
    fun runsOfEveryLengthComeBackWhole() {
        assertArrayEquals(Samples.runs(), unpack(fixture("runs.bz2")))
    }

    @Test
    fun theSameComesOutWhateverTheCallerReadsAtATime() {
        val expected = Samples.runs()
        assertArrayEquals(expected, unpack(fixture("runs.bz2"), chunk = 1))
        assertArrayEquals(expected, unpack(fixture("runs.bz2"), chunk = 7))
        val stream = Bzip2InputStream(ByteArrayInputStream(fixture("hello.txt.bz2")))
        val bytes = generateSequence { stream.read().takeIf { it >= 0 } }.map { it.toByte() }.toList().toByteArray()
        assertEquals("Hello, Tern.\n", String(bytes))
    }

    @Test
    fun streamsOneAfterTheOtherAreReadAsOne() {
        assertArrayEquals(Samples.text(3000, 1) + Samples.text(5000, 2), unpack(fixture("two-streams.bz2")))
    }

    @Test
    fun whatFollowsTheLastStreamIsLeftAlone() {
        assertEquals("Hello, Tern.\n", String(unpack(fixture("hello.txt.bz2") + "not bzip2".toByteArray())))
    }

    @Test
    fun aTarArchiveInsideIsReadFileByFile() {
        val seen = LinkedHashMap<String, ByteArray>()
        Bzip2InputStream(ByteArrayInputStream(fixture("apps.tar.bz2"))).use { input ->
            TarReader.read(input) { entry, body -> seen[entry.name] = body.readBytes() }
        }
        assertEquals(listOf("app/app-arm64-v8a.apk", "app/notes.txt"), seen.keys.toList())
        assertArrayEquals(Samples.text(40_000, 21), seen.getValue("app/app-arm64-v8a.apk"))
        assertEquals("not an app\n", String(seen.getValue("app/notes.txt")))
    }

    @Test
    fun moreThanTheLimitIsRefused() {
        val error = assertThrows(CompressedDataException::class.java) { unpack(fixture("text.bz2"), maxOutput = 100_000) }
        assertTrue(error.message!!.contains("100000"))
        assertEquals(250_000, unpack(fixture("text.bz2"), maxOutput = 250_000).size)
    }

    @Test
    fun dataCutShortIsRefused() {
        val whole = fixture("text.bz2")
        for (length in listOf(3, 10, 40, whole.size / 2, whole.size - 5)) {
            assertThrows(IOException::class.java) { unpack(whole.copyOf(length)) }
        }
    }

    @Test
    fun aChangedByteIsCaughtAndNeverLoopsOrOverruns() {
        val whole = fixture("text.bz2")
        var caught = 0
        for (at in listOf(4, 10, 20, 37, 100, 1000, 5000, whole.size / 2, whole.size - 20, whole.size - 3)) {
            val damaged = whole.copyOf()
            damaged[at] = (damaged[at].toInt() xor 0x21).toByte()
            try {
                val out = unpack(damaged)
                // A change that still decodes has to fail a CRC; nothing passes silently.
                assertEquals("no error at $at", 0, out.size)
            } catch (_: IOException) {
                caught++
            }
        }
        assertEquals(10, caught)
    }

    @Test
    fun dataThatIsNotBzip2IsRefused() {
        assertThrows(CompressedDataException::class.java) { unpack("BZh0 not really".toByteArray()) }
        assertThrows(CompressedDataException::class.java) { unpack("plain text".toByteArray()) }
        assertThrows(CompressedDataException::class.java) { unpack(ByteArray(0)) }
    }

    @Test
    fun anOldRandomisedBlockIsRefusedInWords() {
        val bytes = fixture("hello.txt.bz2")
        // The bit after the block's CRC says the block was randomised, which bzip2 stopped writing in 1999.
        bytes[14] = (bytes[14].toInt() or 0x80).toByte()
        val error = assertThrows(CompressedDataException::class.java) { unpack(bytes) }
        assertTrue(error.message!!.contains("older"))
    }
}
