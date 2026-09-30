package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.PseudoVersion
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Tern writes for Obtainium is read back by Tern's own Obtainium import as the same apps. */
class ObtainiumExportTest {
    private val pin = "ab".repeat(32)

    private val github = AppConfig(
        id = "org.example.app",
        source = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"),
        name = "Example App",
        author = "Example Labs",
        packageName = "org.example.app",
        releases = ReleasePolicy(includePrereleases = true, titleFilter = "^Release", notesFilter = "stable", versionExtract = "v(.+)", minAgeDays = 3, fallbackToOlder = false),
        assets = AssetPolicy(exclude = "debug", matchDevice = false),
        updates = UpdateMode.MANUAL,
        pinnedSigners = listOf(pin),
        categories = listOf("Tools", "Work"),
        favorite = true,
        notes = "Keep an eye on this one",
    )

    private val repo = AppConfig(
        id = "repo",
        source = SourceSpec(SourceTypes.FDROID_REPO, "https://repo.example.org/fdroid/repo", mapOf(SourceOptions.PACKAGE to "org.example.other")),
        name = "Other",
        packageName = "org.example.other",
    )

    private val page = AppConfig(
        id = "page",
        source = SourceSpec(
            SourceTypes.HTML,
            "https://example.org/downloads",
            mapOf(SourceOptions.LINK_FILTER to "app-.*\\.apk", SourceOptions.STEPS to """["/releases/[0-9]+"]""", SourceOptions.SORT to "page"),
        ),
        name = "Page",
    )

    @Test
    fun whatTernWritesTernReadsBackAsTheSameApps() {
        val written = ObtainiumExport.write(listOf(github, repo, page), 1_700_000_000_000, "0.2.0")
        assertEquals(3, written.written)
        val read = ObtainiumImport.read(written.text)
        assertEquals(emptyList<Skipped>(), read.skipped)
        val (app, other, html) = read.apps
        assertEquals(github.source, app.source)
        assertEquals(github.name, app.name)
        assertEquals(github.author, app.author)
        assertEquals(github.packageName, app.packageName)
        assertEquals(github.releases.copy(tagFilter = null), app.releases)
        assertEquals(github.assets, app.assets)
        // Never checked in the background goes out as exempt from background installs and with no notifications, and comes back as that.
        assertEquals(UpdateMode.NOTIFY, app.updates)
        assertTrue(app.muted)
        assertEquals(listOf(pin), app.pinnedSigners)
        assertEquals(github.categories, app.categories)
        assertTrue(app.favorite)
        assertEquals(github.notes, app.notes)
        assertEquals(repo.source, other.source)
        assertEquals(page.source.options, html.source.options)
    }

    @Test
    fun theOptionsObtainiumHasTooComeBackAsTheyWent() {
        val rich = AppConfig(
            id = "rich",
            source = SourceSpec(SourceTypes.GITHUB, "https://github.com/example/rich", mapOf(SourceOptions.VERIFY_LATEST to "true", SourceOptions.ASSET_DATE to "true")),
            name = "Rich",
            releases = ReleasePolicy(
                versionExtract = "v(\\d+)\\.(\\d+)",
                matchGroup = "$1.$2",
                versionFrom = VersionFrom.TITLE,
                order = ReleaseOrder.NAME,
                stayBehind = 1,
                versionFilter = "^2\\.",
            ),
            assets = AssetPolicy(archives = true, innerFilter = "arm64"),
            customName = "My Rich",
            customAuthor = "Me",
            muted = true,
            refreshFirst = true,
            playInstaller = true,
        )
        val headers = RequestHeaders.write(mapOf("Referer" to "https://example.org/"))
        val direct = AppConfig(
            "direct",
            SourceSpec(SourceTypes.DIRECT, "https://example.org/app.apk", mapOf(SourceOptions.HEADERS to headers, SourceOptions.PSEUDO to PseudoVersion.HASH.option)),
            "Direct",
        )
        val (back, backDirect) = ObtainiumImport.read(ObtainiumExport.write(listOf(rich, direct), 0, "0.2.0").text).apps
        assertEquals(rich.source, back.source)
        assertEquals(rich.releases, back.releases)
        assertEquals(rich.assets, back.assets)
        assertEquals(listOf(rich.customName, rich.customAuthor), listOf(back.customName, back.customAuthor))
        assertEquals(listOf(true, true, true), listOf(back.muted, back.refreshFirst, back.playInstaller))
        assertEquals(direct.source, backDirect.source)
        // A pattern without a group named goes out naming the one Tern takes, and comes back unnamed.
        val plain = github.copy(releases = ReleasePolicy(versionExtract = "v(.+)"))
        assertEquals(plain.releases, ObtainiumImport.read(ObtainiumExport.write(listOf(plain), 0, "0.2.0").text).apps.single().releases)
    }

    @Test
    fun aHeaderThatCouldHoldAKeyNeverLeavesForObtainium() {
        val headers = RequestHeaders.write(mapOf("Accept-Language" to "de", "X-Api-Key" to "k123"))
        val direct = AppConfig("direct", SourceSpec(SourceTypes.DIRECT, "https://example.org/app.apk", mapOf(SourceOptions.HEADERS to headers)), "Direct")
        val text = ObtainiumExport.write(listOf(direct), 0, "0.2.0").text
        assertFalse(text, "k123" in text || "X-Api-Key" in text)
        val back = ObtainiumImport.read(text).apps.single()
        assertEquals(mapOf("Accept-Language" to "de"), RequestHeaders.parse(back.source.option(SourceOptions.HEADERS)))
    }

    @Test
    fun theFileHasTheShapeObtainiumChecks() {
        val root = Json.parseObject(ObtainiumExport.write(listOf(github), 0, "0.2.0").text)
        assertEquals(2L, root.long("schemaVersion"))
        val entry = root.array("apps")!!.objects().single()
        assertEquals("GitHub", entry.string("overrideSource"))
        // Obtainium reads these as a JSON text inside the entry, not as an object.
        Json.parseObject(entry.string("additionalSettings")!!)
        Json.parseArray(entry.string("apkUrls")!!)
    }

    @Test
    fun anAppWithoutAPackageNameGoesWithAnIdObtainiumTakesForTemporaryAndComesBackWithout() {
        val entry = Json.parseObject(ObtainiumExport.write(listOf(page), 0, "0.2.0").text).array("apps")!!.objects().single()
        val id = entry.string("id")!!
        assertTrue(id, id.matches(Regex("[0-9a-f]{12}")))
        assertEquals(true, entry.bool("allowIdChange"))
        val back = ObtainiumImport.read(ObtainiumExport.write(listOf(page, github), 0, "0.2.0").text).apps
        assertNull(back[0].packageName)
        assertEquals(page.source, back[0].source)
        assertEquals(github.packageName, back[1].packageName)
    }

    @Test
    fun anIdThatStandsInForAPackageNameIsNotTakenForOne() {
        for (id in listOf("a1b2c3d4e5f6", "0123456789ab", "1700000000000", "tern.a1b2c3d4e5f6")) {
            val text = """[{"id":"$id","url":"https://github.com/example/app","name":"App","additionalSettings":"{}"}]"""
            assertNull(id, ObtainiumImport.read(text).apps.single().packageName)
        }
        // A real package name that only looks a little like one is kept.
        val text = """[{"id":"tern.a1b2c3d4e5f6g","url":"https://github.com/example/app","name":"App","additionalSettings":"{}"}]"""
        assertEquals("tern.a1b2c3d4e5f6g", ObtainiumImport.read(text).apps.single().packageName)
    }

    @Test
    fun aSourceObtainiumHasNoReaderForIsNamedAndLeftOut() {
        val actions = AppConfig("ci", SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app"), "Nightly")
        val written = ObtainiumExport.write(listOf(actions, github), 0, "0.2.0")
        assertEquals(1, written.written)
        assertEquals(listOf("Nightly"), written.left)
    }
}
