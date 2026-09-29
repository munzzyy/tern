package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.engine.real.Importable
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportableOrderTest {
    private fun file(name: String, modifiedAtMs: Long) = SavedFile(name, "Download/Stamp", "/storage/emulated/0/Download/Stamp/$name", modifiedAtMs, 10)

    private fun order(vararg files: SavedFile) = Importable.newestFirst(files.toList()) { it }.map { it.name }

    @Test
    fun theNewestFileComesFirstAndFilesOfOneMomentGoByName() {
        assertEquals(
            listOf("c.json", "a.json", "b.json", "d.json"),
            order(file("d.json", 1_000), file("b.json", 2_000), file("c.json", 3_000), file("a.json", 2_000)),
        )
    }

    @Test
    fun noMoreThanFiftyAreListedAndTheOldestAreTheOnesLeftOut() {
        val many = (1..60).map { file("export-$it.json", it * 1_000L) }.shuffled(Random(7))
        assertEquals((60 downTo 11).map { "export-$it.json" }, Importable.newestFirst(many) { it }.map { it.name })
    }

    @Test
    fun onlyJsonFilesCountAsExports() {
        assertTrue(Importable.isExportName("stamp-apps-2026-09-29.json"))
        assertTrue(Importable.isExportName("OBTAINIUM.JSON"))
        assertFalse(Importable.isExportName("stamp-apps.json.txt"))
        assertFalse(Importable.isExportName("json"))
        assertFalse(Importable.isExportName("app.apk"))
    }
}
