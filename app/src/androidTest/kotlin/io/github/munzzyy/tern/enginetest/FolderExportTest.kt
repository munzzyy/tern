package io.github.munzzyy.tern.enginetest

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.real.RealEngine
import java.io.Closeable
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export and import without a file picker, on a real MediaStore. Nothing here asks what kind of
 * device it runs on, so a phone image and a television image have to give the same answers.
 */
@RunWith(AndroidJUnit4::class)
class FolderExportTest {
    private val resolver = targetContext.contentResolver
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val dropFolder = File(targetContext.getExternalFilesDir(null), "import")

    // A day of its own for each run, years back, so that no file a person or an earlier run left has this run's names.
    private val day: LocalDate = LocalDate.of(2001, 1, 1).plusDays(System.currentTimeMillis() / 1000 % 5000)
    private val noon = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val mark = "enginetest-$day"

    private class Dated(name: String, nowMs: Long) : Closeable {
        private val store = "enginetest-$name.db"
        private val prefs = "enginetest-$name-"
        private val downloads = File(targetContext.filesDir, "enginetest-$name")

        init {
            sweep()
        }

        val engine = RealEngine(targetContext, FakeForge(), storeName = store, prefsPrefix = prefs, nowMs = { nowMs }, downloadsDir = downloads)

        init {
            runBlocking { engine.saveSettings(engine.settings.value.copy(checkEveryMinutes = 0)) }
        }

        fun add(name: String) {
            val config = app(name)
            engine.store.putApp(config, AppState())
            runBlocking { engine.ready() }
            engine.stored[config.id] = StoredApp(config, AppState())
            engine.publish()
        }

        fun names(): List<String> = engine.apps.value.map { it.config.name }

        private fun sweep() {
            targetContext.deleteDatabase(store)
            targetContext.deleteSharedPreferences("${prefs}settings")
            targetContext.deleteSharedPreferences("${prefs}tokens")
            downloads.deleteRecursively()
        }

        override fun close() {
            engine.close()
            sweep()
        }
    }

    private fun exportsOfTheDay(): List<Pair<Long, String>> {
        val found = ArrayList<Pair<Long, String>>()
        resolver.query(downloads, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(1) ?: continue
                if (name.startsWith("tern-apps-$day")) found += rows.getLong(0) to name
            }
        }
        return found
    }

    private fun textOf(name: String): String {
        val id = exportsOfTheDay().single { it.second == name }.first
        return resolver.openInputStream(ContentUris.withAppendedId(downloads, id))!!.use { String(it.readBytes()) }
    }

    private fun drop(name: String, bytes: ByteArray): File {
        dropFolder.mkdirs()
        return File(dropFolder, "$mark-$name").apply { writeBytes(bytes) }
    }

    private fun padded(export: String, size: Int): ByteArray {
        val bytes = export.toByteArray()
        return bytes + ByteArray(size - bytes.size) { ' '.code.toByte() }
    }

    private suspend fun refused(kind: ProblemKind, call: suspend () -> Any?) {
        val answer = try {
            call()
        } catch (e: ProblemException) {
            assertEquals(e.problem.message, kind, e.problem.kind)
            assertTrue("the sentence is empty", e.problem.message.isNotBlank())
            return
        }
        throw AssertionError("was not refused, and answered $answer")
    }

    @Before
    @After
    fun sweep() {
        for ((id, _) in exportsOfTheDay()) resolver.delete(ContentUris.withAppendedId(downloads, id), null, null)
        dropFolder.listFiles().orEmpty().filter { it.name.startsWith("enginetest-") }.forEach { it.delete() }
        targetContext.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("enginetest-") }.forEach { it.delete() }
    }

    @Test
    fun anExportLandsInTheDownloadFolderAndASecondOfThatDayGetsANumber() = runBlocking {
        Dated("folder-export", noon).use { h ->
            h.add("Wren")
            h.add("Dunnock")
            val first = h.engine.exportToFolder()
            assertEquals("tern-apps-$day.json", first.name)
            assertEquals("Download/Tern", first.place)
            assertTrue(first.path, first.path.endsWith("/Download/Tern/tern-apps-$day.json"))
            assertEquals(listOf("Dunnock", "Wren"), TernExport.read(textOf(first.name)).map { it.name })
            assertEquals(textOf(first.name).toByteArray().size.toLong(), first.sizeBytes)

            // MediaStore keeps the time of a change in whole seconds, and the order asked for below is by that time.
            Thread.sleep(1_100)
            val second = h.engine.exportToFolder()
            assertEquals("tern-apps-$day-2.json", second.name)
            assertEquals(listOf("Dunnock", "Wren"), TernExport.read(textOf(second.name)).map { it.name })

            val listed = h.engine.importableFiles().filter { it.name.startsWith("tern-apps-$day") }
            assertEquals(listOf(second.name, first.name), listed.map { it.name })
            assertEquals(listOf(second.path, first.path), listed.map { it.path })
            assertEquals(listOf("Download/Tern", "Download/Tern"), listed.map { it.place })
            assertTrue("${listed.map { it.modifiedAtMs }}", listed[0].modifiedAtMs > listed[1].modifiedAtMs)
        }
    }

    @Test
    fun anExportComesBackThroughTheListOfFiles() = runBlocking {
        val saved = Dated("folder-out", noon).use { h ->
            h.add("Wren")
            h.add("Dunnock")
            h.engine.exportToFolder()
        }
        Dated("folder-in", noon).use { h ->
            val listed = h.engine.importableFiles().single { it.name == saved.name }
            assertEquals(saved.path, listed.path)
            assertEquals(2, h.engine.importFromFile(listed).added)
            assertEquals(listOf("Dunnock", "Wren"), h.names())
            assertEquals(2, h.engine.importFromFile(listed).alreadyPresent)
        }
    }

    @Test
    fun aJsonFileInTheAppsOwnFolderIsListedAndImported() = runBlocking {
        val friend = drop("friend.json", TernExport.write(listOf(app("Wren")), 0, "test").toByteArray())
        drop("notes.txt", "not a list".toByteArray())
        Dated("folder-drop", noon).use { h ->
            val listed = h.engine.importableFiles()
            val found = listed.single { it.name == friend.name }
            assertEquals("Android/data/${targetContext.packageName}/files/import", found.place)
            assertEquals(friend.path, found.path)
            assertEquals(friend.length(), found.sizeBytes)
            assertEquals(emptyList<String>(), listed.map { it.name }.filter { it.endsWith(".txt") })
            assertEquals(1, h.engine.importFromFile(found).added)
            assertEquals(listOf("Wren"), h.names())
        }
    }

    @Test
    fun aFileTheListDoesNotNameIsRefused() = runBlocking {
        val export = TernExport.write(listOf(app("Wren")), 0, "test").toByteArray()
        val outside = File(targetContext.cacheDir, "$mark-outside.json").apply { writeBytes(export) }
        val inside = drop("inside.json", export)
        Dated("folder-refuse", noon).use { h ->
            val around = File(dropFolder, "../../cache/${outside.name}").path
            for (path in listOf(outside.path, around, inside.path + "/", inside.path.uppercase(), "")) {
                refused(ProblemKind.NOT_FOUND) { h.engine.importFromFile(SavedFile(inside.name, "Download/Tern", path, 0, export.size.toLong())) }
            }
            assertEquals(emptyList<String>(), h.names())
        }
    }

    @Test
    fun twoMebibytesAreReadAndOneByteMoreIsRefused() = runBlocking {
        val export = TernExport.write(listOf(app("Wren")), 0, "test")
        val full = drop("full.json", padded(export, 2 * 1024 * 1024))
        val over = drop("over.json", padded(export, 2 * 1024 * 1024 + 1))
        Dated("folder-size", noon).use { h ->
            val listed = h.engine.importableFiles()
            refused(ProblemKind.UNSUPPORTED) { h.engine.importFromFile(listed.single { it.name == over.name }) }
            assertEquals(emptyList<String>(), h.names())
            assertEquals(1, h.engine.importFromFile(listed.single { it.name == full.name }).added)
        }
    }

    @Test
    fun aFileThatIsNoExportIsRefused() = runBlocking {
        val page = drop("page.json", "<html></html>".toByteArray())
        Dated("folder-parse", noon).use { h ->
            val listed = h.engine.importableFiles()
            refused(ProblemKind.PARSE) { h.engine.importFromFile(listed.single { it.name == page.name }) }
            assertEquals(emptyList<String>(), h.names())
        }
    }

    @Test
    fun noMoreThanFiftyFilesAreListedNewestFirst() = runBlocking {
        val export = TernExport.write(listOf(app("Wren")), 0, "test").toByteArray()
        for (n in 1..55) drop("many-$n.json", export)
        Dated("folder-many", noon).use { h ->
            val listed = h.engine.importableFiles()
            assertEquals(50, listed.size)
            assertEquals(listed.map { it.modifiedAtMs }.sortedDescending(), listed.map { it.modifiedAtMs })
            assertEquals(emptyList<String>(), listed.map { it.name }.filterNot { it.startsWith("$mark-many-") })
        }
    }

    private companion object {
        fun app(name: String) = AppConfig(name.lowercase(), SourceSpec(SourceTypes.GITHUB, "https://github.com/example/${name.lowercase()}"), name)
    }
}
