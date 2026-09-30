package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.core.apk.ApkFormatException
import io.github.munzzyy.tern.core.apk.FileSource
import io.github.munzzyy.tern.core.apk.ZipIndex
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a base and its splits, fetched one by one, become the one zip the gate reads as a bundle. */
class PartsZipTest {
    private val work: File = Files.createTempDirectory("parts").toFile().apply { deleteOnExit() }
    private val target = File(work, "parts.zip")
    private val scratch = File(work, "part.apk")

    /** A zip with [entries], deflated, as an APK or an archive of APKs comes. */
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File = File(work, name).apply {
        ZipOutputStream(FileOutputStream(this)).use { zip ->
            for ((entry, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun apk(name: String, marker: String): File = zip(name, "AndroidManifest.xml" to marker.toByteArray(), "classes.dex" to ByteArray(300) { it.toByte() })

    private fun joined(vararg parts: FetchedPart, maxBytes: Long = Long.MAX_VALUE, maxApks: Int = 512): Map<String, ByteArray> {
        PartsZip.join(parts.toList(), target, scratch, maxBytes, maxApks)
        return FileSource(target).use { source ->
            val index = ZipIndex.open(source)
            // Stored, so that each APK is read in place and only the chosen ones are unpacked.
            assertTrue(index.entries.all { it.isStored })
            index.entries.associate { it.name to index.read(it, Int.MAX_VALUE) }
        }
    }

    @Test
    fun anApkGoesInWholeUnderItsOwnName() {
        val base = apk("base.bin", "base")
        val split = apk("split.bin", "split")
        val got = joined(FetchedPart("org.example.app_1.0.apks", base), FetchedPart("config.arm64_v8a.zip", split))
        assertEquals(listOf("org.example.app_1.0.apk", "config.arm64_v8a.apk"), got.keys.toList())
        assertArrayEquals(base.readBytes(), got.getValue("org.example.app_1.0.apk"))
        assertArrayEquals(split.readBytes(), got.getValue("config.arm64_v8a.apk"))
        assertFalse(scratch.exists())
    }

    @Test
    fun aZipGivesTheApksItHolds() {
        val inner = apk("inner.bin", "split").readBytes()
        val base = apk("base.bin", "base")
        val holder = zip("holder.bin", "split_config.xxhdpi.apk" to inner, "readme.txt" to "hello".toByteArray())
        val got = joined(FetchedPart("base.zip", base), FetchedPart("config.xxhdpi.zip", holder))
        assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), got.keys.toList())
        assertArrayEquals(inner, got.getValue("split_config.xxhdpi.apk"))
        assertFalse(scratch.exists())
    }

    @Test
    fun aNameThatComesTwiceIsNumbered() {
        val got = joined(FetchedPart("app.apk", apk("a.bin", "a")), FetchedPart("app.apk", apk("b.bin", "b")), FetchedPart("/app.apk", apk("c.bin", "c")))
        assertEquals(listOf("app.apk", "2/app.apk", "3/app.apk"), got.keys.toList())
    }

    @Test
    fun aPartThatHoldsNoApkIsRefused() {
        val holder = zip("holder.bin", "readme.txt" to "hello".toByteArray())
        assertThrows(ApkFormatException::class.java) { PartsZip.join(listOf(FetchedPart("x.zip", holder)), target, scratch, Long.MAX_VALUE, 512) }
        val text = File(work, "text.bin").apply { writeText("not a zip at all") }
        assertThrows(ApkFormatException::class.java) { PartsZip.join(listOf(FetchedPart("y.apk", text)), target, scratch, Long.MAX_VALUE, 512) }
    }

    @Test
    fun partsMayNotHoldMoreThanOneInstallTakes() {
        val a = apk("a.bin", "a")
        val b = apk("b.bin", "b")
        assertThrows(PartsZip.TooLarge::class.java) { PartsZip.join(listOf(FetchedPart("a.apk", a), FetchedPart("b.apk", b)), target, scratch, a.length() + 1, 512) }
        assertThrows(PartsZip.TooLarge::class.java) { PartsZip.join(listOf(FetchedPart("a.apk", a), FetchedPart("b.apk", b)), target, scratch, Long.MAX_VALUE, 1) }
    }

    @Test
    fun aSplitIsNamedForTheLastPartOfItsAddress() {
        assertEquals("config.arm64_v8a.zip", PartsZip.nameOf("https://static.rustore.ru/2026/9/16/ab/config.arm64_v8a.zip"))
        assertEquals("split.zip", PartsZip.nameOf("https://example.org/files/split.zip?token=abc#part"))
        assertEquals("split", PartsZip.nameOf("https://example.org/"))
        assertEquals("config.xxhdpi.apk", PartsZip.apkName("config.xxhdpi.zip"))
        assertEquals("base.apk", PartsZip.apkName("base.apk"))
        assertEquals("split.apk", PartsZip.apkName("split"))
    }

    @Test
    fun anObbFileKeepsItsNameAndNothingThatCouldLeaveItsFolder() {
        assertEquals("main.3.org.example.game.obb", ObbNames.of("Android/obb/org.example.game/main.3.org.example.game.obb"))
        assertEquals("patch.3.org.example.game.OBB", ObbNames.of("patch.3.org.example.game.OBB"))
        assertEquals("main.obb", ObbNames.of("../../../../data/main.obb"))
        for (bad in listOf("main.3.apk", "..\\..\\main.obb", ".obb", ".hidden.obb", "main\u0000.obb", "main\n.obb", "main‮.obb", "a".repeat(252) + ".obb", "dir/")) {
            assertNull(bad, ObbNames.of(bad))
        }
        assertEquals("/storage/emulated/10/Android/obb/org.example.game", ObbNames.folder(10, "org.example.game"))
    }
}
