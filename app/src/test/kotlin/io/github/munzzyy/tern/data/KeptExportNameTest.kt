package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.engine.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptExportNameTest {
    @Test
    fun anEmptyNameGivesTheUsualNameOfTheFormat() {
        assertEquals("tern-apps.json", KeptExportName.of(null, ExportFormat.TERN))
        assertEquals("obtainium-export.json", KeptExportName.of("  ", ExportFormat.OBTAINIUM))
        assertNull(KeptExportName.clean(".json"))
        assertNull(KeptExportName.clean("///"))
    }

    @Test
    fun aTypedNameLosesWhatNoFileNameHoldsAndEndsInJson() {
        assertEquals("backup.json", KeptExportName.clean("backup"))
        assertEquals("backup.json", KeptExportName.clean("backup.json"))
        assertEquals("backup.json", KeptExportName.clean("backup.JSON"))
        assertEquals("myapps.json", KeptExportName.clean(" my/apps:*? "))
        assertEquals("my apps.json", KeptExportName.clean("my apps"))
        assertEquals("hidden.json", KeptExportName.clean("..hidden"))
        assertEquals("tabs.json", KeptExportName.clean("ta\tbs"))
    }

    @Test
    fun aLongNameIsCutAndStillEndsInJson() {
        val name = KeptExportName.clean("a".repeat(500))!!
        assertTrue(name.endsWith(".json"))
        assertEquals(KeptExportName.MAX_LENGTH, name.length)
    }
}
