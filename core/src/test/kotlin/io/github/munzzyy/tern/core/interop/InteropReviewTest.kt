package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropReviewTest {
    private fun export(vararg apps: String) = """{"schemaVersion":2,"apps":[${apps.joinToString(",")}]}"""

    private fun app(id: String, url: String, override: String?, settings: String = "{}") =
        Json.write(
            Json.obj(
                "id" to id, "url" to url, "name" to id, "author" to "someone",
                "additionalSettings" to settings, "overrideSource" to override, "pinned" to false, "categories" to emptyList<String>(),
            ),
        )

    private fun only(text: String): AppConfig = ObtainiumImport.read(text).let { result ->
        assertTrue("skipped: ${result.skipped}", result.skipped.isEmpty())
        result.apps.single()
    }

    @Test
    fun anOverriddenFDroidAppKeepsItsPackage() {
        val fdroid = only(export(app("org.example.app", "https://f-droid.org/packages/org.example.app", "FDroid")))
        assertEquals(SourceTypes.FDROID, fdroid.source.type)
        assertEquals("org.example.app", fdroid.source.option(SourceOptions.PACKAGE))

        val izzy = only(export(app("org.example.app", "https://apt.izzysoft.de/fdroid/index/apk/org.example.app", "IzzyOnDroid")))
        assertEquals("org.example.app", izzy.source.option(SourceOptions.PACKAGE))
        assertEquals("https://apt.izzysoft.de/fdroid/index/apk/org.example.app", izzy.source.url)
    }

    @Test
    fun aThirdPartyRepositoryKeepsItsBaseAndItsApp() {
        val fromQuery = only(export(app("org.example.app", "https://repo.example.org/fdroid/repo?appId=org.example.app", "FDroidRepo")))
        assertEquals(SourceSpec(SourceTypes.FDROID_REPO, "https://repo.example.org/fdroid/repo", mapOf(SourceOptions.PACKAGE to "org.example.app")), fromQuery.source)

        val fromSetting = only(export(app("x", "https://repo.example.org/fdroid/repo", "FDroidRepo", """{"appIdOrName":"org.example.other"}""")))
        assertEquals("org.example.other", fromSetting.source.option(SourceOptions.PACKAGE))
    }

    @Test
    fun forgeAddressesAreStoredInCanonicalForm() {
        assertEquals("https://github.com/Example/App", only(export(app("a.b", "https://github.com/Example/App/releases", "GitHub"))).source.url)
        assertEquals("https://github.com/Example/App", only(export(app("a.b", "http://www.github.com/Example/App.git", null))).source.url)
        assertEquals("https://gitlab.com/group/sub/app", only(export(app("a.b", "https://gitlab.com/group/sub/app/-/releases", null))).source.url)
        assertEquals("https://codeberg.org/someone/app", only(export(app("a.b", "https://codeberg.org/someone/app/releases", null))).source.url)
        assertEquals("https://git.example.org/someone/app", only(export(app("a.b", "https://git.example.org/someone/app", "Codeberg"))).source.url)
    }

    @Test
    fun intermediatePagesBecomeSteps() {
        val settings = """{"customLinkFilterRegex":"\\.apk$","intermediateLink":[{"customLinkFilterRegex":"downloads/latest"},{"customLinkFilterRegex":"mirror\\d"},{"customLinkFilterRegex":""}]}"""
        val html = only(export(app("a.b", "https://example.org/app", "HTML", settings)))
        assertEquals("\\.apk$", html.source.option(SourceOptions.LINK_FILTER))
        assertEquals(listOf("downloads/latest", "mirror\\d"), Json.parseArray(html.source.option(SourceOptions.STEPS)!!).strings())
    }

    @Test
    fun anAddressThatIsNotHttpsCapableIsSkippedWithAReason() {
        val result = ObtainiumImport.read(export(app("a.b", "ftp://example.org/app.apk", null), app("c.d", "not a url at all", "HTML")))
        assertTrue(result.apps.isEmpty())
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun aLinkWithRawJsonIsStillRead() {
        val raw = """obtainium://app/{"id":"a.b","url":"https://github.com/example/app","name":"App"}"""
        val link = ObtainiumLink.parse(raw) as ObtainiumLink.App
        assertEquals("https://github.com/example/app", Json.parseObject(link.json).string("url"))
        assertEquals(ObtainiumLink.Add("https://github.com/example/app"), ObtainiumLink.parse("obtainium://add/https://github.com/example/app"))
        assertEquals(ObtainiumLink.Add("https://github.com/example/app"), ObtainiumLink.parse("OBTAINIUM://add/https%3A%2F%2Fgithub.com%2Fexample%2Fapp"))
    }

    @Test
    fun aStoredAppIsCheckedWhenItIsRead() {
        fun encoded(change: (MutableMap<String, Any?>) -> Unit): String {
            val base = AppConfigJson.encode(AppConfig("id1", SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), "App")).fields
            val map: MutableMap<String, Any?> = LinkedHashMap(base)
            change(map)
            return Json.write(Json.of(map))
        }
        fun decode(text: String) = AppConfigJson.decode(Json.parseObject(text))

        assertThrows(AppConfigJsonException::class.java) { decode(encoded { it["source"] = mapOf("type" to "github", "url" to "javascript:alert(1)") }) }
        assertThrows(AppConfigJsonException::class.java) { decode(encoded { it["source"] = mapOf("type" to "warez", "url" to "https://example.org/x") }) }
        assertThrows(AppConfigJsonException::class.java) { decode(encoded { it["name"] = "x".repeat(5000) }) }
        assertThrows(AppConfigJsonException::class.java) { decode(encoded { it["packageName"] = "../../etc" }) }

        assertEquals("https://example.org/x", decode(encoded { it["source"] = mapOf("type" to "html", "url" to "http://EXAMPLE.org/x") }).source.url)
        val pins = decode(encoded { it["pinnedSigners"] = listOf("AA:" + "bb:".repeat(30) + "cc", "junk", "A".repeat(64)) }).pinnedSigners
        assertEquals(listOf("aa" + "bb".repeat(30) + "cc", "a".repeat(64)), pins)
        assertEquals(365, decode(encoded { it["releases"] = mapOf("minAgeDays" to 99999999999L) }).releases.minAgeDays)
        assertEquals(0, decode(encoded { it["releases"] = mapOf("minAgeDays" to -4) }).releases.minAgeDays)
    }
}
