package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.Importable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DroppedLinkTest {
    private val root: File = Files.createTempDirectory("dropped").toFile().apply { deleteOnExit() }
    private val folder = File(root, "import").apply { mkdirs() }
    private val secret = File(root, "private.json").apply { writeText("""{"token":"not for an import"}""") }

    @Test
    fun aLinkInTheImportFolderIsNotListed() {
        File(folder, "real.json").writeText("{}")
        Files.createSymbolicLink(File(folder, "link.json").toPath(), secret.toPath())
        assertEquals(listOf("real.json"), Importable.dropped(folder, 500).map { it.name })
    }

    @Test
    fun aFileThatBecameALinkAfterItWasListedIsNotOpened() {
        val file = File(folder, "swapped.json").apply { writeText("{}") }
        assertEquals(listOf("swapped.json"), Importable.dropped(folder, 500).map { it.name })
        file.delete()
        Files.createSymbolicLink(file.toPath(), secret.toPath())
        try {
            Importable.openDropped(file).use { it.readBytes() }
            fail("a link was followed to a private file")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aPlainFileOpens() {
        val file = File(folder, "plain.json").apply { writeText("""{"apps":[]}""") }
        assertEquals("""{"apps":[]}""", Importable.openDropped(file).use { it.readBytes().decodeToString() })
    }
}
