package io.github.munzzyy.tern.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TarReaderTest {
    /** A tar archive as tar writes one: a header block per entry, the body padded to whole blocks, two empty blocks at the end. */
    private class Tar {
        private val out = ByteArrayOutputStream()

        fun file(name: String, body: ByteArray, type: Char = '0', prefix: String = "", damage: Boolean = false): Tar {
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, 0)
            "0000644".toByteArray().copyInto(header, 100)
            String.format("%011o", body.size).toByteArray().copyInto(header, 124)
            header[156] = type.code.toByte()
            "ustar".toByteArray().copyInto(header, 257)
            prefix.toByteArray().copyInto(header, 345)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val sum = header.sumOf { it.toInt() and 0xff } + if (damage) 1 else 0
            String.format("%06o", sum).toByteArray().copyInto(header, 148)
            header[154] = 0
            out.write(header)
            out.write(body)
            out.write(ByteArray((512 - body.size % 512) % 512))
            return this
        }

        fun end(): ByteArray {
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }

    private fun names(bytes: ByteArray): List<Pair<String, String>> {
        val seen = ArrayList<Pair<String, String>>()
        TarReader.read(ByteArrayInputStream(bytes)) { entry, body -> seen += entry.name to String(body.readBytes()) }
        return seen
    }

    @Test
    fun everyRegularFileIsReadInOrderAndTheRestPassedOver() {
        val tar = Tar()
            .file("app/", ByteArray(0), type = '5')
            .file("app/app-arm64.apk", "arm".toByteArray())
            .file("app/link.apk", ByteArray(0), type = '2')
            .file("app/app-x86.apk", ByteArray(700) { 'x'.code.toByte() })
            .end()
        val seen = names(tar)
        assertEquals(listOf("app/app-arm64.apk", "app/app-x86.apk"), seen.map { it.first })
        assertEquals("arm", seen[0].second)
        assertEquals(700, seen[1].second.length)
    }

    @Test
    fun whatIsLeftUnreadOfAFileIsSkipped() {
        val tar = Tar().file("a.apk", ByteArray(1500) { 1 }).file("b.apk", "b".toByteArray()).end()
        val seen = ArrayList<String>()
        TarReader.read(ByteArrayInputStream(tar)) { entry, _ -> seen += entry.name }
        assertEquals(listOf("a.apk", "b.apk"), seen)
    }

    @Test
    fun aLongNameAndAPrefixMakeTheWholeName() {
        val long = "releases/" + "very-long-folder-name/".repeat(6) + "app.apk"
        val tar = Tar()
            .file("././@LongLink", long.toByteArray(), type = 'L')
            .file("ignored-short-name", "one".toByteArray())
            .file("app.apk", "two".toByteArray(), prefix = "build/outputs")
            .end()
        assertEquals(listOf(long to "one", "build/outputs/app.apk" to "two"), names(tar))
    }

    @Test
    fun aDamagedOrShortArchiveIsRefused() {
        val damaged = Tar().file("a.apk", "a".toByteArray(), damage = true).end()
        assertThrows(ApkFormatException::class.java) { names(damaged) }
        val whole = Tar().file("a.apk", ByteArray(2000)).end()
        assertThrows(ApkFormatException::class.java) { names(whole.copyOf(900)) }
    }

    @Test
    fun anArchiveOfNothingHoldsNothing() {
        assertEquals(emptyList<Pair<String, String>>(), names(Tar().end()))
        assertEquals(emptyList<Pair<String, String>>(), names(ByteArray(0)))
    }
}
