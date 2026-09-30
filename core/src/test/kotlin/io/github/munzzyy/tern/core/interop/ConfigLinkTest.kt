package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link Tern shares is one Obtainium's own reader takes, and Tern reads back as the same settings. */
class ConfigLinkTest {
    private val pin = "cd".repeat(32)

    private val app = AppConfig(
        id = "org.example.app",
        source = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"),
        name = "Example App",
        author = "Example Labs",
        packageName = "org.example.app",
        releases = ReleasePolicy(includePrereleases = true, titleFilter = "^Release", minAgeDays = 2),
        assets = AssetPolicy(include = "arm64 & more", matchDevice = false),
        pinnedSigners = listOf(pin),
        categories = listOf("Work"),
        favorite = true,
        notes = "The key is under the mat",
        customName = "My App",
    )

    /** What Tern's Add screen does with a link: Obtainium's reader, given the one app the link carries. */
    private fun readBack(link: String): AppConfig {
        val carried = ObtainiumLink.parse(link) as ObtainiumLink.App
        return ObtainiumImport.read("[${carried.json}]").apps.single()
    }

    @Test
    fun theLinkCarriesTheSettingsAndComesBackAsThem() {
        val link = ConfigLink.of(app)!!
        assertTrue(link.startsWith("obtainium://app/"))
        val back = readBack(link)
        assertEquals(app.source, back.source)
        assertEquals(app.name, back.name)
        assertEquals(app.author, back.author)
        assertEquals(app.packageName, back.packageName)
        assertEquals(app.releases, back.releases)
        assertEquals(app.assets, back.assets)
        assertEquals(listOf(pin), back.pinnedSigners)
        assertEquals("My App", back.customName)
    }

    @Test
    fun theLinkHasTheShapeObtainiumsOwnLinksHave() {
        val carried = ObtainiumLink.parse(ConfigLink.of(app)!!) as ObtainiumLink.App
        val entry = Json.parseObject(carried.json)
        assertEquals(setOf("id", "url", "author", "name", "preferredApkIndex", "additionalSettings", "overrideSource"), entry.fields.keys)
        assertEquals("GitHub", entry.string("overrideSource"))
        // Obtainium reads its settings as a JSON text inside the entry.
        Json.parseObject(entry.string("additionalSettings")!!)
    }

    @Test
    fun whatIsThePersonsOwnStaysBehind() {
        val back = readBack(ConfigLink.of(app)!!)
        assertNull(back.notes)
        assertEquals(emptyList<String>(), back.categories)
        assertFalse(back.favorite)
        assertFalse(ConfigLink.of(app)!!.contains("mat"))
    }

    @Test
    fun aHeaderThatCouldHoldAKeyNeverGoesIntoALink() {
        val headers = RequestHeaders.write(mapOf("User-Agent" to "Mozilla/5.0", "X-Api-Key" to "hunter2secret", "PRIVATE-TOKEN" to "glpat-abc"))
        val direct = AppConfig("direct", SourceSpec(SourceTypes.DIRECT, "https://example.org/app.apk", mapOf(SourceOptions.HEADERS to headers)), "Direct")
        val link = ConfigLink.web(direct)!!
        assertFalse(link.contains("hunter2secret"))
        assertFalse(link.contains("glpat"))
        assertFalse(link.contains("creds"))
        val back = readBack(link)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0"), RequestHeaders.of(back.source))

        val onlySecret = direct.copy(source = direct.source.copy(options = mapOf(SourceOptions.HEADERS to RequestHeaders.write(mapOf("X-Api-Key" to "hunter2secret")))))
        assertNull(readBack(ConfigLink.of(onlySecret)!!).source.option(SourceOptions.HEADERS))
    }

    @Test
    fun aLinkedAppNeverInstallsByItself() {
        val auto = app.copy(updates = UpdateMode.AUTO)
        assertEquals(UpdateMode.NOTIFY, readBack(ConfigLink.of(auto)!!).updates)
        // Obtainium has no mode that is never checked: such an app arrives checked, notified of nothing, and still not installing.
        val manual = readBack(ConfigLink.of(app.copy(updates = UpdateMode.MANUAL))!!)
        assertEquals(UpdateMode.NOTIFY, manual.updates)
        assertTrue(manual.muted)
    }

    @Test
    fun anAppWithoutAPackageNameGoesWithAnIdObtainiumReplacesAtTheFirstInstall() {
        val page = AppConfig("page", SourceSpec(SourceTypes.HTML, "https://example.org/downloads", mapOf(SourceOptions.LINK_FILTER to "app-.*\\.apk")), "Page")
        val carried = ObtainiumLink.parse(ConfigLink.of(page)!!) as ObtainiumLink.App
        assertTrue(Json.parseObject(carried.json).string("id")!!.matches(Regex("[0-9a-f]{12}")))
        val back = ObtainiumImport.read("[${carried.json}]").apps.single()
        assertNull(back.packageName)
        assertEquals(page.source.options, back.source.options)
    }

    @Test
    fun theWebLinkIsTheSameLinkBehindObtainiumsPage() {
        val web = ConfigLink.web(app)!!
        assertEquals(ObtainiumLink.WEB_REDIRECT + ConfigLink.of(app), web)
        assertEquals(readBack(ConfigLink.of(app)!!), readBack(web))
    }

    @Test
    fun aSourceObtainiumCannotFollowHasNoLink() {
        val actions = AppConfig("ci", SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app"), "Nightly")
        assertNull(ConfigLink.of(actions))
        assertNull(ConfigLink.web(actions))
    }

    @Test
    fun aLinkTooLongForTernToReadBackIsNotMade() {
        val long = app.copy(releases = ReleasePolicy(titleFilter = "release ".repeat(1_000)))
        assertNull(ConfigLink.of(long))
        assertNull(ConfigLink.web(long))
        assertTrue(ConfigLink.web(app)!!.length <= ConfigLink.MAX_LENGTH)
    }
}
