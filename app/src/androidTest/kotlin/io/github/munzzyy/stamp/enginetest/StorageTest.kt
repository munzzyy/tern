package io.github.munzzyy.stamp.enginetest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.engine.InstallRecord
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.AssetPolicy
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.net.Validator
import io.github.munzzyy.stamp.data.AppState
import io.github.munzzyy.stamp.data.GateBlock
import io.github.munzzyy.stamp.data.InvalidHostException
import io.github.munzzyy.stamp.data.PendingInstall
import io.github.munzzyy.stamp.data.Store
import io.github.munzzyy.stamp.data.TokenVault
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private fun freshStore(name: String): Store {
        targetContext.deleteDatabase(name)
        return Store(targetContext, name)
    }

    @Test
    fun anAppAndItsStateSurviveReopening() {
        val config = AppConfig(
            id = "a1",
            source = SourceSpec("forgejo", "https://forge.test/example/app", mapOf("fingerprint" to "ab".repeat(32))),
            name = "Fixture",
            author = "example",
            packageName = "com.example.app",
            releases = ReleasePolicy(includePrereleases = true, tagFilter = "^v\\d", minAgeDays = 3, skippedReleaseId = "v0.9"),
            assets = AssetPolicy(include = "arm64", exclude = "debug", matchDevice = false),
            updates = UpdateMode.AUTO,
            pinnedSigners = listOf("cd".repeat(32)),
            categories = listOf("tools"),
            favorite = true,
            notes = "mine",
        )
        val release = Release(
            id = "v1.0", version = "1.0", versionCode = 10, title = "One", notes = "x".repeat(30_000), publishedAtMs = 5, prerelease = true,
            pageUrl = "https://forge.test/r", assets = listOf(Asset("app.apk", "https://forge.test/app.apk", 123, "ef".repeat(32), needsAuth = true, signers = listOf("01".repeat(32)))),
        )
        val state = AppState(
            releases = listOf(release),
            lastCheckedMs = 99,
            checkProblem = Problem(ProblemKind.RATE_LIMITED, "wait", 1234),
            record = InstallRecord("v0.9", "0.9", 9, "aa".repeat(32), 77),
            pending = PendingInstall(7, "com.example.app", "v1.0", "1.0", 10, null, 123, "https://forge.test/app.apk", 55, waitingForUser = true),
            block = GateBlock("v1.0", "https://forge.test/app.apk", Problem(ProblemKind.SIGNER_MISMATCH, "no")),
            seenReleaseId = "v1.0",
            announcedReleaseId = "v1.0",
        )
        freshStore("storage-roundtrip.db").use { it.putApp(config, state) }
        Store(targetContext, "storage-roundtrip.db").use { reopened ->
            val back = reopened.app("a1")!!
            assertEquals(config, back.config)
            assertEquals(state.copy(releases = listOf(release.copy(notes = "x".repeat(20_000)))), back.state)
        }
        targetContext.deleteDatabase("storage-roundtrip.db")
    }

    @Test
    fun validatorsRoundTripAndGoAwayByPrefix() {
        freshStore("storage-validators.db").use { store ->
            store.put("forgejo|https://forge.test/a|releases", Validator("\"e\"", "Mon"))
            store.put("forgejo|https://forge.test/b|releases", Validator("\"f\"", null))
            assertEquals(Validator("\"e\"", "Mon"), store.get("forgejo|https://forge.test/a|releases"))
            store.removeValidators("forgejo|https://forge.test/a|")
            assertNull(store.get("forgejo|https://forge.test/a|releases"))
            assertEquals(Validator("\"f\"", null), store.get("forgejo|https://forge.test/b|releases"))
        }
        targetContext.deleteDatabase("storage-validators.db")
    }

    @Test
    fun aHalfMegabyteValidatorComesBackWhole() {
        val big = buildString { while (length < 500 * 1024) append("{\"versionCode\":${length},\"name\":\"é中\"},") }
        freshStore("storage-bigvalidator.db").use { it.put("fdroid-repo|https://example.org/repo|app:com.example.app", Validator(big, "1727568000000")) }
        Store(targetContext, "storage-bigvalidator.db").use { reopened ->
            val back = reopened.get("fdroid-repo|https://example.org/repo|app:com.example.app")!!
            assertEquals(big.length, back.etag!!.length)
            assertEquals(big, back.etag)
            assertEquals("1727568000000", back.lastModified)
        }
        targetContext.deleteDatabase("storage-bigvalidator.db")
    }

    @Test
    fun theEventLogKeepsTheNewestFiveHundred() {
        freshStore("storage-events.db").use { store ->
            repeat(520) { store.addEvent(it.toLong(), "a1", "Fixture", EventKind.UPDATE_FOUND, "event $it") }
            val events = store.events()
            assertEquals(500, events.size)
            assertEquals("event 519", events.first().message)
            assertEquals("event 20", events.last().message)
        }
        val path = targetContext.getDatabasePath("storage-events.db").path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM events", null).use { c ->
                c.moveToFirst()
                assertEquals("rows kept on disk", 500, c.getInt(0))
            }
        }
        targetContext.deleteDatabase("storage-events.db")
    }

    @Test
    fun aTokenOpensOnlyForItsOwnHost() {
        val prefs = "storage-tokens"
        targetContext.deleteSharedPreferences(prefs)
        val vault = TokenVault(targetContext, prefs, alias = "stamp-test-tokens")
        vault.put("API.GitHub.com", "ghp_secret_value")
        assertEquals("ghp_secret_value", vault.tokenFor("api.github.com"))
        assertNull(vault.tokenFor("github.com"))
        assertNull(vault.tokenFor("api.github.com.evil.test"))
        assertEquals(listOf("api.github.com"), vault.hosts())

        val raw = targetContext.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        val sealed = raw.getString("api.github.com", null)!!
        assertFalse(sealed.contains("ghp_secret_value"))
        raw.edit().putString("evil.test", sealed).commit()
        assertNull("a ciphertext moved to another host opened", vault.tokenFor("evil.test"))

        try {
            vault.put("not a host/", "x")
            fail("accepted a malformed host")
        } catch (_: InvalidHostException) {
            assertTrue(true)
        }
        vault.put("api.github.com", null)
        assertNull(vault.tokenFor("api.github.com"))
        targetContext.deleteSharedPreferences(prefs)
    }
}
