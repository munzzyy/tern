package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.source.SourceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigJsonTest {
    private fun sample() = AppConfig(
        id = "org.example.app",
        source = SourceSpec("github", "https://github.com/example/app", mapOf("branch" to "main")),
        name = "Example",
        author = "Example Dev",
        packageName = "org.example.app",
        releases = ReleasePolicy(includePrereleases = true, minAgeDays = 2, tagFilter = "^v"),
        assets = AssetPolicy(include = "arm64", matchDevice = false),
        updates = UpdateMode.AUTO,
        trackOnly = true,
        pinnedSigners = listOf("a".repeat(64)),
        categories = listOf("tools"),
        favorite = true,
        notes = "test app",
    )

    @Test
    fun roundTripsAllFields() {
        val original = sample()
        val decoded = AppConfigJson.decode(AppConfigJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun encodesSchemaField() {
        val json = AppConfigJson.encode(sample())
        assertEquals(1L, json.long("schema"))
    }

    @Test
    fun decodeIgnoresUnknownFields() {
        val json = AppConfigJson.encode(sample())
        val withExtra = Json.obj(*json.fields.entries.map { it.key to it.value }.toTypedArray(), "somethingUnknown" to "value")
        val decoded = AppConfigJson.decode(withExtra)
        assertEquals("org.example.app", decoded.id)
    }

    @Test
    fun decodeDefaultsMissingOptionalFields() {
        val minimal = Json.obj(
            "id" to "x",
            "source" to Json.obj("type" to "direct", "url" to "https://example.com/a.apk"),
            "name" to "X",
        )
        val decoded = AppConfigJson.decode(minimal)
        assertEquals(UpdateMode.NOTIFY, decoded.updates)
        assertFalse(decoded.trackOnly)
        assertTrue(decoded.categories.isEmpty())
        assertEquals(true, decoded.releases.fallbackToOlder)
    }

    @Test(expected = AppConfigJsonException::class)
    fun decodeThrowsOnMissingId() {
        AppConfigJson.decode(Json.obj("source" to Json.obj("type" to "direct", "url" to "https://x/a.apk"), "name" to "X"))
    }

    @Test(expected = AppConfigJsonException::class)
    fun decodeThrowsOnMissingSource() {
        AppConfigJson.decode(Json.obj("id" to "x", "name" to "X"))
    }

    @Test(expected = AppConfigJsonException::class)
    fun decodeThrowsOnUnknownUpdateMode() {
        val json = Json.obj(
            "id" to "x",
            "source" to Json.obj("type" to "direct", "url" to "https://x/a.apk"),
            "name" to "X",
            "updates" to "NOT_A_MODE",
        )
        AppConfigJson.decode(json)
    }

    /** Every field added for release, file and per-app choices set away from its default, so one left out of the file shows. */
    private fun everything() = sample().copy(
        source = SourceSpec(
            "html", "https://example.com/download",
            mapOf(SourceOptions.FIRST_LINK to "true", SourceOptions.HEADERS to """{"User-Agent":"Example/1.0"}""", SourceOptions.PSEUDO to "hash"),
        ),
        releases = sample().releases.copy(matchGroup = "$1.$2", versionFrom = VersionFrom.DATE, order = ReleaseOrder.NAME, stayBehind = 3, versionFilter = "^2\\."),
        assets = sample().assets.copy(archives = true, innerFilter = "arm64"),
        customName = "Chosen Name",
        customAuthor = "Chosen Author",
        muted = true,
        refreshFirst = true,
        playInstaller = true,
    )

    @Test
    fun roundTripsEveryNewField() {
        val original = everything()
        assertEquals(original, AppConfigJson.decode(AppConfigJson.encode(original)))
        assertEquals(original, AppConfigJson.decode(Json.parseObject(Json.write(AppConfigJson.encode(original)))))
        assertEquals(1L, AppConfigJson.encode(original).long("schema"))
    }

    @Test
    fun anAppOfASourceThatOffersNoFileIsAlwaysTrackOnly() {
        val mirror = sample().copy(source = SourceSpec(SourceTypes.APKMIRROR, "https://www.apkmirror.com/apk/example/app"), trackOnly = false)
        assertTrue(AppConfigJson.decode(AppConfigJson.encode(mirror)).trackOnly)
        assertNull(AppConfigJson.decode(AppConfigJson.encode(sample().copy(releases = ReleasePolicy()))).releases.minAgeDays)
    }

    @Test
    fun anOlderFileWithoutTheNewFieldsLoadsWithTheirDefaults() {
        val older = """{"schema": 1, "id": "org.example.app", "source": {"type": "github", "url": "https://github.com/example/app", "options": {}},
            "name": "Example", "author": null, "packageName": null,
            "releases": {"includePrereleases": false, "tagFilter": null, "titleFilter": null, "notesFilter": null, "versionExtract": null,
                "minAgeDays": 0, "skippedReleaseId": null, "fallbackToOlder": true},
            "assets": {"include": null, "exclude": null, "matchDevice": true},
            "updates": "NOTIFY", "trackOnly": false, "pinnedSigners": [], "categories": [], "favorite": false, "notes": null}"""
        val decoded = AppConfigJson.decode(Json.parseObject(older))
        // A wait that was stored stays the app's own; only an app without one follows the setting for all apps.
        assertEquals(ReleasePolicy(minAgeDays = 0), decoded.releases)
        assertEquals(AssetPolicy(), decoded.assets)
        assertNull(decoded.customName)
        assertNull(decoded.customAuthor)
        assertFalse(decoded.muted)
        assertFalse(decoded.refreshFirst)
        assertFalse(decoded.playInstaller)
    }

    @Test
    fun refusesWhatTheNewFieldsCannotHold() {
        fun decode(vararg fields: Pair<String, Any?>) =
            AppConfigJson.decode(Json.obj("id" to "x", "source" to Json.obj("type" to "direct", "url" to "https://example.com/a.apk"), "name" to "X", *fields))
        assertThrows(AppConfigJsonException::class.java) { decode("releases" to Json.obj("order" to "NEWEST")) }
        assertThrows(AppConfigJsonException::class.java) { decode("releases" to Json.obj("versionFrom" to "tag")) }
        assertThrows(AppConfigJsonException::class.java) { decode("releases" to Json.obj("matchGroup" to "1".repeat(201))) }
        assertThrows(AppConfigJsonException::class.java) { decode("customName" to "n".repeat(201)) }
        assertThrows(AppConfigJsonException::class.java) { decode("customAuthor" to "a".repeat(201)) }
        assertEquals(5, decode("releases" to Json.obj("stayBehind" to 50)).releases.stayBehind)
        assertEquals(0, decode("releases" to Json.obj("stayBehind" to -2)).releases.stayBehind)
    }
}
