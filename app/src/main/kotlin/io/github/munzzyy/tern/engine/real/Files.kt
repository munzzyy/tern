package io.github.munzzyy.tern.engine.real

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SavedFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId

/** The name of an export: tern-apps-2026-09-29.json, and tern-apps-2026-09-29-2.json for the second of that day. */
internal object ExportNames {
    private const val PREFIX = "tern-apps-"
    private const val SUFFIX = ".json"
    private const val MAX_PER_DAY = 500

    fun forDay(nowMs: Long, zone: ZoneId, taken: Collection<String>): String {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        val plain = "$PREFIX$day$SUFFIX"
        if (plain !in used) return plain
        for (number in 2..MAX_PER_DAY) {
            val numbered = "$PREFIX$day-$number$SUFFIX"
            if (numbered !in used) return numbered
        }
        return plain
    }
}

internal object Importable {
    const val MAX_LISTED = 50

    fun isExportName(name: String): Boolean = name.endsWith(".json", ignoreCase = true)

    /** Plain files in [folder], never a link, which could point at a file of Tern's own. */
    fun dropped(folder: File, most: Int): List<File> =
        folder.listFiles().orEmpty().asSequence()
            .take(most)
            .filter { isExportName(it.name) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .toList()

    /** Opens [file] only if it is still no link when it is opened. */
    fun openDropped(file: File): InputStream =
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    fun <T> newestFirst(found: List<T>, file: (T) -> SavedFile): List<T> =
        found.sortedWith(compareByDescending<T> { file(it).modifiedAtMs }.thenBy { file(it).name }).take(MAX_LISTED)
}

/**
 * Export and import without a file picker. A file the app makes itself goes into the shared
 * download folder through MediaStore, which needs no permission from Android 10 on, and the app
 * can read such a file back for as long as it stays installed.
 */
internal class Files(context: Context, private val texts: Texts, private val nowMs: () -> Long) {
    private val c = context.applicationContext
    private val resolver = c.contentResolver
    private val downloads: Uri get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // Deprecated as a way to open files; here it only names the shared folder in a path a person reads.
    @Suppress("DEPRECATION")
    private val shared: File get() = Environment.getExternalStorageDirectory()

    private class Entry(val file: SavedFile, val uri: Uri?)

    fun save(text: String, fixedName: String? = null): SavedFile {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val name = fixedName ?: ExportNames.forDay(nowMs(), ZoneId.systemDefault(), asked { exports() }.orEmpty().map { it.file.name })
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, Device.EXPORT_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, PLACE)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = asked { resolver.insert(downloads, values) } ?: throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportNoPlace()))
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("MediaStore opened no file")
            out.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (ex: IOException) {
            asked { resolver.delete(uri, null, null) }
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        } catch (ex: RuntimeException) {
            asked { resolver.delete(uri, null, null) }
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        }
        // MediaStore gives the file another name when one of that name exists that this app cannot see.
        val written = asked { exports(uri) }?.firstOrNull()?.file ?: SavedFile(name, PLACE, File(shared, "$PLACE/$name").path, nowMs(), 0)
        return written.copy(sizeBytes = bytes.size.toLong())
    }

    /**
     * Copies [source] into Download/Tern as [name], for the person to keep or pass on. MediaStore
     * picks another name when one of that name is there already.
     */
    fun saveCopy(source: File, name: String, mime: String): SavedFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, PLACE)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = asked { resolver.insert(downloads, values) } ?: throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportNoPlace()))
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("MediaStore opened no file")
            out.use { target -> source.inputStream().use { it.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (ex: IOException) {
            asked { resolver.delete(uri, null, null) }
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        } catch (ex: RuntimeException) {
            asked { resolver.delete(uri, null, null) }
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        }
        val written = asked {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: name
        return SavedFile(written, PLACE, File(shared, "$PLACE/$written").path, nowMs(), source.length())
    }

    /**
     * Writes [text] as [name] in Download/Tern, over the file of that name this app made before,
     * so a kept export stays one file instead of one a day.
     */
    fun keep(name: String, text: String): SavedFile {
        val existing = asked { exports() }.orEmpty().firstOrNull { it.file.name == name && it.uri != null }
        val uri = existing?.uri ?: return save(text, name)
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("MediaStore opened no file")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (ex: IOException) {
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        } catch (ex: RuntimeException) {
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.exportFailed(ex.message)))
        }
        return existing.file
    }

    fun list(): List<SavedFile> = entries().map { it.file }

    /** Only a file that [list] names is read, so a path from anywhere else opens nothing. */
    fun read(file: SavedFile): ByteArray {
        val entry = entries().firstOrNull { it.file.path == file.path }
            ?: throw ProblemException(Problem(ProblemKind.NOT_FOUND, texts.importFileGone()))
        if (entry.file.sizeBytes > MAX_BYTES) throw tooLarge()
        return try {
            open(entry).use { Capped.read(it, MAX_BYTES) }
        } catch (_: Capped.TooLarge) {
            throw tooLarge()
        } catch (ex: IOException) {
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.unreadableFile(ex.message)))
        } catch (ex: SecurityException) {
            throw ProblemException(Problem(ProblemKind.STORAGE, texts.unreadableFile(ex.message)))
        }
    }

    private fun open(entry: Entry): InputStream =
        if (entry.uri == null) Importable.openDropped(File(entry.file.path)) else resolver.openInputStream(entry.uri) ?: throw IOException("MediaStore opened no file")

    private fun entries(): List<Entry> = Importable.newestFirst(asked { exports() }.orEmpty() + dropped()) { it.file }

    /** MediaStore runs in another process and answers a failure with whichever RuntimeException it likes. */
    private fun <T> asked(call: () -> T): T? = try {
        call()
    } catch (_: RuntimeException) {
        null
    }

    /** Rows of the download collection that this app made; Android shows an app without permissions no others. */
    private fun exports(only: Uri? = null): List<Entry> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING,
        )
        val found = ArrayList<Entry>()
        resolver.query(only ?: downloads, columns, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { rows ->
            var seen = 0
            while (rows.moveToNext() && seen < MAX_ROWS) {
                seen++
                val name = rows.getString(1) ?: continue
                val folder = rows.getString(2).orEmpty().trim('/')
                if (rows.getInt(5) != 0 || !Importable.isExportName(name) || !folder.equals(PLACE, ignoreCase = true)) continue
                val file = SavedFile(
                    name = name,
                    place = folder,
                    path = File(shared, "$folder/$name").path,
                    modifiedAtMs = rows.getLong(3) * 1000,
                    sizeBytes = rows.getLong(4),
                )
                found += Entry(file, ContentUris.withAppendedId(downloads, rows.getLong(0)))
            }
        }
        return found
    }

    /** Files a person put into the app's own folder on shared storage, with adb or a file manager. */
    private fun dropped(): List<Entry> {
        val folder = dropFolder() ?: return emptyList()
        val place = folder.path.removePrefix(shared.path).trim('/')
        return Importable.dropped(folder, MAX_ROWS)
            .map { Entry(SavedFile(it.name, place, it.path, it.lastModified(), it.length()), null) }
    }

    private fun dropFolder(): File? {
        val folder = File(c.getExternalFilesDir(null) ?: return null, DROP_FOLDER)
        folder.mkdirs()
        return folder.takeIf { it.isDirectory }
    }

    private fun tooLarge() = ProblemException(Problem(ProblemKind.UNSUPPORTED, texts.importTooLarge(MAX_BYTES)))

    companion object {
        const val PLACE = "Download/Tern"
        const val DROP_FOLDER = "import"
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_ROWS = 500
    }
}
