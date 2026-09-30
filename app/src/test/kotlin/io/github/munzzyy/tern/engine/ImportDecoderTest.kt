package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.ImportDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportDecoderTest {
    private fun decode(text: String) = ImportDecoder.decode(text.toByteArray(), ImportSentences)

    @Test
    fun anExportOfTernsIsRead() {
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
        assertEquals(listOf("Copy" to "Tern could not read this address as an app on APKPure"), decoded.skipped)
    }

    @Test
    fun anAppOnALocalNetworkIsLeftOutAndNamed() {
        val decoded = decode(
            """[{"url":"https://github.com/example/wren","name":"Wren"},
               {"url":"https://192.168.1.20/group/app","name":"Router","overrideSource":"GitLab"},
               {"url":"https://127.0.0.1/app.apk","name":"Loop","overrideSource":"DirectAPKLink"},
               {"url":"https://git.lan/group/app","name":"Shelf","overrideSource":"Codeberg"}]""",
        )
        assertEquals(listOf("Wren"), decoded.apps.map { it.name })
        assertEquals(listOf("Router", "Loop", "Shelf").map { it to "importLocalAddress" }, decoded.skipped)
    }

    @Test
    fun aFileWithNothingButLocalAddressesSaysSoAndIsNoEmptyFile() {
        val decoded = decode("""[{"url":"https://10.0.0.5/group/app","name":"Shelf","overrideSource":"GitLab"}]""")
        assertEquals(emptyList<String>(), decoded.apps.map { it.name })
        assertEquals(listOf("Shelf" to "importLocalAddress"), decoded.skipped)
    }

    @Test
    fun aByteOrderMarkInFrontIsSkipped() {
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + exportOf("Wren").toByteArray()
        assertEquals(listOf("Wren"), ImportDecoder.decode(marked, ImportSentences).apps.map { it.name })
    }

    @Test
    fun anExportOfTernsThatCannotBeReadIsNotTakenForAnEmptyOne() {
        val newer = exportOf("Wren").replace(Regex("\"schema\":\\s*1"), "\"schema\": 2")
        refused(ProblemKind.PARSE, "importUnreadableExport(Unsupported export schema 2)") { decode(newer) }
        refused(ProblemKind.PARSE, "importUnreadableExport(Missing apps array)") { decode("""{"format":"tern-export","schema":1}""") }
    }

    @Test
    fun whatIsNoExportIsRefused() {
        for (text in listOf("", "tern", "<html></html>", "{}", "7", """{"format":"tern-export"""")) {
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
