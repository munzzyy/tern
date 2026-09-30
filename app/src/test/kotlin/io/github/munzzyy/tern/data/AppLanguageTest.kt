package io.github.munzzyy.tern.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    /** A resource folder's qualifier as a language tag: `pt-rBR` is `pt-BR`, and Android's old `in` is `id`. */
    private fun tag(qualifier: String): String = qualifier.replace("-r", "-").let { if (it == "in") "id" else it }

    @Test
    fun everyTranslationCanBeChosenAndNothingElse() {
        val translated = File("src/main/res").listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .map { tag(it.name.removePrefix("values-")) }
        assertEquals((translated + "en").sorted(), AppLanguage.TAGS.sorted())
    }

    @Test
    fun theBuildKeepsEveryLanguageThatCanBeChosen() {
        val build = File("build.gradle.kts").readText()
        val filters = Regex("localeFilters \\+= listOf\\(([^)]*)\\)").find(build)!!.groupValues[1]
        val kept = Regex("\"([^\"]+)\"").findAll(filters).map { tag(it.groupValues[1]) }.toList()
        assertEquals(AppLanguage.TAGS.sorted(), kept.sorted())
    }

    @Test
    fun namesAreInTheirOwnLanguage() {
        assertEquals("Deutsch", AppLanguage.nameOf("de"))
        assertEquals("Français", AppLanguage.nameOf("fr"))
        assertTrue(AppLanguage.nameOf("pt-BR").startsWith("Português"))
        assertEquals(AppLanguage.TAGS.size, AppLanguage.byName().distinct().size)
    }
}
