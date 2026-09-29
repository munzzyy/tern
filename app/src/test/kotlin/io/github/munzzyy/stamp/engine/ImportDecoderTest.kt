package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.engine.real.ImportDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportDecoderTest {
    private fun decode(text: String) = ImportDecoder.decode(text.toByteArray(), ImportSentences)

    @Test
    fun anExportOfStampsIsRead() {
        val decoded = decode(exportOf("Wren", "Dunnock"))
        assertEquals(listOf("Wren", "Dunnock"), decoded.apps.map { it.name })
        assertEquals(emptyList<Pair<String, String>>(), decoded.skipped)
    }

    @Test
    fun anExportOfObtainiumsIsReadAndSaysWhatItLeftOut() {
        val decoded = decode(
            """[{"id":"org.example.wren","url":"https://github.com/example/wren","name":"Wren"},
               {"url":"https://apkpure.com/wren","name":"Copy","overrideSource":"APKPure"}]""",
        )
        assertEquals(listOf("https://github.com/example/wren"), decoded.apps.map { it.source.url })
        assertEquals(listOf("Copy" to "APKPure is not supported by Stamp; find the developer's own release page instead"), decoded.skipped)
    }

    @Test
    fun aByteOrderMarkInFrontIsSkipped() {
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + exportOf("Wren").toByteArray()
        assertEquals(listOf("Wren"), ImportDecoder.decode(marked, ImportSentences).apps.map { it.name })
    }

    @Test
    fun anExportOfStampsThatCannotBeReadIsNotTakenForAnEmptyOne() {
        val newer = exportOf("Wren").replace(Regex("\"schema\":\\s*1"), "\"schema\": 2")
        refused(ProblemKind.PARSE, "importUnreadableExport(Unsupported export schema 2)") { decode(newer) }
        refused(ProblemKind.PARSE, "importUnreadableExport(Missing apps array)") { decode("""{"format":"stamp-export","schema":1}""") }
    }

    @Test
    fun whatIsNoExportIsRefused() {
        for (text in listOf("", "stamp", "<html></html>", "{}", "7", """{"format":"stamp-export"""")) {
            refused(ProblemKind.PARSE, "importNotAnExport") { decode(text) }
        }
    }

    @Test
    fun aDoorMayPutItsOwnWordsOnWhatIsNoExport() {
        refused(ProblemKind.PARSE, "from the door") { ImportDecoder.decode("{}".toByteArray(), ImportSentences, "from the door") }
    }

    @Test
    fun aFileThatHoldsNoAppsIsNotAnImportOfNothing() {
        for (empty in listOf("[]", """{"apps":[]}""", exportOf())) {
            refused(ProblemKind.PARSE, "importEmpty") { decode(empty) }
        }
    }
}
