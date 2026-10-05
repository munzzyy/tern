package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.PendingInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadSweepTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val listed = Downloader.key("v2", "https://example.org/app-v2.apk")
    private val gone = Downloader.key("v1", "https://example.org/app-v1.apk")

    private fun root() = temp.newFolder("downloads")

    /** The files a download of [key] leaves for [appId], last touched [ageMs] before now. */
    private fun files(root: File, appId: String, key: String, finished: Boolean, ageMs: Long = 0): List<File> {
        val dir = File(root, Downloader.folderName(appId)).apply { mkdirs() }
        val base = Downloader.baseOf(key)
        val made = listOf(File(dir, "$base.meta"), File(dir, if (finished) "$base.bin" else "$base.part"))
        for (file in made) {
            file.writeText(key)
            file.setLastModified(now - ageMs)
        }
        return made
    }

    @Test
    fun theFolderOfAnAppNoLongerInTheListGoes() {
        val root = root()
        val kept = files(root, "kept", listed, finished = true)
        val dropped = files(root, "dropped", listed, finished = true)
        Downloader.sweep(root, mapOf("kept" to setOf(listed)), now)
        assertTrue(kept.all { it.isFile })
        assertFalse(File(root, Downloader.folderName("dropped")).exists())
        assertFalse(dropped.any { it.exists() })
    }

    @Test
    fun aListedReleaseStaysAndOneNoLongerListedGoes() {
        val root = root()
        val stays = files(root, "app", listed, finished = true)
        val goes = files(root, "app", gone, finished = true)
        Downloader.sweep(root, mapOf("app" to setOf(listed)), now)
        assertTrue(stays.all { it.isFile })
        assertFalse(goes.any { it.exists() })
    }

    @Test
    fun aKeyWhoseReleaseIdHoldsABarStays() {
        val root = root()
        val key = Downloader.key("file:https://example.org/app.apk|\"5d41402a\"|https://example.org/app-arm64.apk", "https://example.org/app.apk")
        val stays = files(root, "page", key, finished = true)
        Downloader.sweep(root, mapOf("page" to setOf(key)), now)
        assertTrue(stays.all { it.isFile })
    }

    @Test
    fun aDownloadCutShortAndLeftForTwoWeeksGoes() {
        val root = root()
        val old = files(root, "app", listed, finished = false, ageMs = Downloader.ABANDONED_MS + 60_000)
        Downloader.sweep(root, mapOf("app" to setOf(listed)), now)
        assertFalse(old.any { it.exists() })
    }

    @Test
    fun aFreshPartOfAKeptKeyStays() {
        val root = root()
        val fresh = files(root, "app", listed, finished = false, ageMs = Downloader.ABANDONED_MS - 60_000)
        Downloader.sweep(root, mapOf("app" to setOf(listed)), now)
        assertTrue(fresh.all { it.isFile })
    }

    @Test
    fun aFinishedFileOfAListedReleaseStaysHoweverOld() {
        val root = root()
        val kept = files(root, "app", listed, finished = true, ageMs = 10 * Downloader.ABANDONED_MS)
        Downloader.sweep(root, mapOf("app" to setOf(listed)), now)
        assertTrue(kept.all { it.isFile })
    }

    @Test
    fun anAppLeftAloneKeepsEverything() {
        val root = root()
        val unlisted = files(root, "busy", gone, finished = true)
        val old = files(root, "busy", listed, finished = false, ageMs = 2 * Downloader.ABANDONED_MS)
        Downloader.sweep(root, mapOf("busy" to null), now)
        assertTrue((unlisted + old).all { it.isFile })
    }

    @Test
    fun withEveryAppAndKeyKeptAndNothingOldNothingGoes() {
        val root = root()
        val all = files(root, "app", listed, finished = true) + files(root, "app", gone, finished = false) + files(root, "other", listed, finished = true)
        Downloader.sweep(root, mapOf("app" to setOf(listed, gone), "other" to setOf(listed)), now)
        assertTrue(all.all { it.isFile })
    }

    private val older = Downloader.key("v0", "https://example.org/app-v0.apk")
    private val release = Release("v2", "2.0", assets = listOf(Asset("app-v2.apk", "https://example.org/app-v2.apk")))
    private val waiting = PendingInstall(
        sessionId = 7, packageName = "com.example.app", releaseId = "v1", version = "1.0", versionCode = 1,
        fileSha256 = null, fileSize = null, assetUrl = "https://example.org/app-v1.apk", startedAtMs = now,
    )

    /**
     * What the engine keeps of the stored apps [ids] when every one lists [release] and nothing else
     * says otherwise: [held] have a fetch under way, [busy] are being installed, [pending] wait on an
     * install and [unreadable] cannot be read.
     */
    private fun keepMap(
        ids: Set<String>,
        held: Set<String> = emptySet(),
        busy: Set<String> = emptySet(),
        pending: Set<String> = emptySet(),
        unreadable: Set<String> = emptySet(),
    ) = Downloader.keepMap(ids, held, { id ->
        if (id in unreadable) null else AppState(releases = listOf(release), pending = waiting.takeIf { id in pending })
    }) { it in busy }

    /** Sweeps with [keep] a folder for "app" holding a finished file of [listed] and one of [older], which no release names. */
    private fun sweepApp(keep: Map<String, Set<String>?>): Pair<List<File>, List<File>> {
        val root = root()
        val listedFiles = files(root, "app", listed, finished = true)
        val olderFiles = files(root, "app", older, finished = true)
        Downloader.sweep(root, keep, now)
        return listedFiles to olderFiles
    }

    @Test
    fun anAppNothingIsDoingWithKeepsOnlyWhatItsReleasesName() {
        val keep = keepMap(setOf("app"))
        assertEquals(mapOf("app" to setOf(listed)), keep)
        val (listedFiles, olderFiles) = sweepApp(keep)
        assertTrue(listedFiles.all { it.isFile })
        assertFalse(olderFiles.any { it.exists() })
    }

    @Test
    fun anAppBeingInstalledIsLeftAlone() {
        val keep = keepMap(setOf("app"), busy = setOf("app"))
        assertNull(keep["app"])
        assertTrue(sweepApp(keep).second.all { it.isFile })
    }

    @Test
    fun anAppWaitingOnAnInstallIsLeftAlone() {
        val keep = keepMap(setOf("app"), pending = setOf("app"))
        assertNull(keep["app"])
        assertTrue(sweepApp(keep).second.all { it.isFile })
    }

    @Test
    fun anAppAFetchIsUnderWayForIsLeftAlone() {
        val keep = keepMap(setOf("app"), held = setOf("app"))
        assertNull(keep["app"])
        assertTrue(sweepApp(keep).second.all { it.isFile })
    }

    @Test
    fun anAppTheStoreCannotReadIsLeftAlone() {
        val keep = keepMap(setOf("app"), unreadable = setOf("app"))
        assertNull(keep["app"])
        assertTrue(sweepApp(keep).second.all { it.isFile })
    }

    @Test
    fun aFetchForAnAppNotStoredKeepsItsFolder() {
        val keep = keepMap(emptySet(), held = setOf("app"))
        assertEquals(mapOf("app" to null), keep)
        val (listedFiles, olderFiles) = sweepApp(keep)
        assertTrue((listedFiles + olderFiles).all { it.isFile })
    }

    @Test
    fun aMissingRootIsNoTrouble() {
        Downloader.sweep(File(temp.root, "never-made"), mapOf("app" to setOf(listed)), now)
    }

    @Test
    fun theKeysOfAnAppAreThoseOfEveryListedFileAndOfTheInstallItWaitsOn() {
        val split = "https://example.org/v2/split_config.arm64_v8a.apk"
        val release = Release(
            "v2", "2.0",
            assets = listOf(Asset("app-v2.apk", "https://example.org/app-v2.apk", parts = listOf(split))),
            sourceArchives = listOf(Asset("source.zip", "https://example.org/v2.zip")),
        )
        val pending = PendingInstall(
            sessionId = 7, packageName = "com.example.app", releaseId = "v1", version = "1.0", versionCode = 1,
            fileSha256 = null, fileSize = null, assetUrl = "https://example.org/app-v1.apk", startedAtMs = now,
        )
        val keys = Downloader.keysOf(AppState(releases = listOf(release), pending = pending))
        assertEquals(
            setOf(
                listed,
                Downloader.key("v2", split),
                Downloader.key("v2", "https://example.org/v2.zip"),
                Downloader.key("v1", "https://example.org/app-v1.apk"),
            ),
            keys,
        )
        assertEquals(emptySet<String>(), Downloader.keysOf(AppState()))
    }
}
