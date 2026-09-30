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
        assertEquals(UpdateMode.MANUAL, app.updates)
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
    fun aSourceObtainiumHasNoReaderForIsNamedAndLeftOut() {
        val actions = AppConfig("ci", SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app"), "Nightly")
        val written = ObtainiumExport.write(listOf(actions, github), 0, "0.2.0")
        assertEquals(1, written.written)
        assertEquals(listOf("Nightly"), written.left)
    }
}
