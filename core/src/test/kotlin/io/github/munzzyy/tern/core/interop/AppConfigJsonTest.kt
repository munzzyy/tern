package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
