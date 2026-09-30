package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
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
