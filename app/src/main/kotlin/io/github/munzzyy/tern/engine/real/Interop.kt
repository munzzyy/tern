package io.github.munzzyy.tern.engine.real

import android.net.Uri
import io.github.munzzyy.tern.BuildConfig
import androidx.core.content.FileProvider
import io.github.munzzyy.tern.core.interop.ObtainiumExport
import io.github.munzzyy.tern.core.interop.ObtainiumImport
import io.github.munzzyy.tern.core.interop.ObtainiumImportException
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.interop.TernExportException
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.SettingsJson
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.install.Downloader
import io.github.munzzyy.tern.install.OtherAppInstaller
import io.github.munzzyy.tern.install.StepFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The sentences an import can fail with, kept apart from Android so that the doors run on the JVM. */
interface ImportTexts {
    fun importNotAnExport(): String
    fun importEmpty(): String
    fun importLocalAddress(): String
    fun importUnreadableExport(detail: String?): String
    fun importTooLarge(limitBytes: Int): String
    fun linkNotHttps(): String
    fun linkNotAnAddress(): String
    fun linkLeavesHttps(): String
    fun linkNotFound(): String
    fun linkRefused(status: Int): String
    fun linkUnreachable(detail: String?): String
    fun linkTooSlow(): String
    fun linkNotAnExport(): String
    fun serverStatus(code: Int): String
    fun checkRateLimited(untilMs: Long?): String
}

internal class Decoded(val apps: List<AppConfig>, val skipped: List<Pair<String, String>>, val settings: JsonObject? = null)

/** Reads a stream to its end and gives up once it is longer than allowed or has taken too long. */
internal object Capped {
    class TooLarge : IOException("Longer than the limit")

    class TooSlow : IOException("Took too long")

    fun read(input: InputStream, limit: Int, deadlineMs: Long? = null, nowMs: () -> Long = System::currentTimeMillis): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw TooLarge()
            out.write(buffer, 0, n)
            if (deadlineMs != null && nowMs() > deadlineMs) throw TooSlow()
        }
        return out.toByteArray()
    }
}

/** The one decoder every import goes through, whichever door the bytes came in by. */
internal object ImportDecoder {
    private const val BYTE_ORDER_MARK = 0xFEFF
    private const val TERN_FORMAT = "tern-export"

    fun decode(bytes: ByteArray, texts: ImportTexts, notAnExport: String = texts.importNotAnExport()): Decoded {
        val decoded = withoutLocalAddresses(read(bytes, texts, notAnExport), texts)
        // An empty list reads as an export of nothing, and "imported 0 apps" would pass for success.
        if (decoded.apps.isEmpty() && decoded.skipped.isEmpty()) throw ProblemException(Problem(ProblemKind.PARSE, texts.importEmpty()))
        return decoded
    }

    /** A file from somebody else must not make this device call into its own network. An address that is local has to be added by hand. */
    private fun withoutLocalAddresses(decoded: Decoded, texts: ImportTexts): Decoded {
        val (local, other) = decoded.apps.partition { Urls.isLocal(Urls.host(it.source.url)) }
        if (local.isEmpty()) return decoded
        return Decoded(other, decoded.skipped + local.map { it.name to texts.importLocalAddress() }, decoded.settings)
    }

    private fun read(bytes: ByteArray, texts: ImportTexts, notAnExport: String): Decoded {
        val text = String(bytes, Charsets.UTF_8).trimStart { it.code == BYTE_ORDER_MARK }
        return try {
            val file = TernExport.readFile(text)
            Decoded(file.apps, emptyList(), file.settings)
        } catch (tern: TernExportException) {
            // Obtainium's reader finds no app in an export of Tern's and would report an import of nothing.
            if (saysItIsTerns(text)) throw ProblemException(Problem(ProblemKind.PARSE, texts.importUnreadableExport(tern.message)))
            try {
                val result = ObtainiumImport.read(text)
                Decoded(result.apps, result.skipped.map { it.name to it.reason }, result.settings)
            } catch (_: ObtainiumImportException) {
                throw ProblemException(Problem(ProblemKind.PARSE, notAnExport))
            }
        }
    }

    private fun saysItIsTerns(text: String): Boolean = try {
        Json.parseObject(text).string("format") == TERN_FORMAT
    } catch (_: JsonException) {
        false
    }
}

internal class Interop(private val e: RealEngine) {
    private val files = Files(e.context, e.texts, e.nowMs)
    val kept = AutoExport(e, files) { format -> export(null, format).first }
    private val links = Links(e.http, e.texts, e.nowMs)
    private val oneAtATime = Mutex()

    suspend fun importFrom(uri: Uri): ImportSummary = bring { ImportDecoder.decode(read(uri), e.texts) }

    suspend fun importFromFile(file: SavedFile): ImportSummary = bring { ImportDecoder.decode(files.read(file), e.texts) }

    suspend fun importFromLink(url: String): ImportSummary = bring { links.read(url) }

    /** For bytes that are already here, such as a file another device handed over. */
    suspend fun importBytes(bytes: ByteArray): ImportSummary = bring {
        if (bytes.size > MAX_BYTES) throw ProblemException(Problem(ProblemKind.UNSUPPORTED, e.texts.importTooLarge(MAX_BYTES)))
        ImportDecoder.decode(bytes, e.texts)
    }

    suspend fun importableFiles(): List<SavedFile> = runInterruptible(Dispatchers.IO) { files.list() }

    /**
     * Downloads one file of one release and puts a copy in Download/Tern, as it came. Nothing is
     * checked or installed: the file is for the person to keep or pass on.
     */
    suspend fun saveFile(appId: String, releaseId: String, assetUrl: String): SavedFile {
        val t = e.texts
        val stored = e.stored[appId] ?: throw ProblemException(Problem(ProblemKind.NOT_FOUND, t.noRelease()))
        val release = stored.state.releases.firstOrNull { it.id == releaseId } ?: throw ProblemException(Problem(ProblemKind.NOT_FOUND, t.noRelease()))
        val asset = release.assets.firstOrNull { it.url == assetUrl } ?: throw ProblemException(Problem(ProblemKind.NOT_FOUND, t.noFileForDevice(null)))
        val key = Downloader.key(release.id, asset.url)
        var fresh = false
        try {
            val from = runInterruptible(Dispatchers.IO) { e.registry.resolve(stored.config.source, asset, e.sourceContext()) }
            val sameHost = Urls.host(from.url) == Urls.host(asset.url)
            val authorization = if (asset.needsAuth && sameHost) e.tokens.tokenFor(Urls.host(asset.url))?.let { "Bearer $it" } else null
            val download = e.downloader.fetch(appId, key, from.url, authorization, from.headers) { _, _ -> }
            fresh = !download.reused
            return runInterruptible(Dispatchers.IO) { files.saveCopy(download.file, savedName(asset.name), mimeOf(asset.name)) }
        } catch (ex: SourceException) {
            throw ProblemException(e.checks.problemOf(ex))
        } catch (ex: StepFailure) {
            throw ProblemException(ex.problem)
        } finally {
            // A file kept for an install that is to be tried again stays; one fetched only to be saved goes.
            if (fresh && !e.settings.value.keepInstallers) e.downloader.discard(appId, key)
        }
    }

    private suspend fun bring(door: () -> Decoded): ImportSummary {
        val decoded = runInterruptible(Dispatchers.IO) { door() }
        return oneAtATime.withLock { withContext(Dispatchers.IO) { store(decoded) } }
    }

    private fun store(decoded: Decoded): ImportSummary {
        val skipped = ArrayList(decoded.skipped)
        var added = 0
        var present = 0
        val fresh = ArrayList<String>()
        val withPins = ArrayList<String>()
        val withFilters = ArrayList<String>()
        val askedForMore = ArrayList<String>()
        for (imported in decoded.apps.take(MAX_APPS)) {
            if (e.findBySpec(imported.source) != null) {
                present++
                continue
            }
            val config = try {
                e.validated(Arrivals.stored(imported, if (e.stored.containsKey(imported.id)) e.newId() else imported.id, e.builtIn))
            } catch (ex: IllegalArgumentException) {
                skipped += imported.name to (ex.message ?: "")
                continue
            }
            val state = AppState(addedAtMs = e.nowMs())
            e.store.putApp(config, state)
            e.stored[config.id] = StoredApp(config, state)
            e.event(config.id, EventKind.IMPORTED, e.texts.eventImported())
            fresh += config.id
            added++
            if (imported.pinnedSigners.isNotEmpty()) withPins += config.shownName
            if (hasFilters(config)) withFilters += config.shownName
            if (imported.updates == UpdateMode.AUTO) askedForMore += config.shownName
        }
        e.publish()
        if (fresh.isNotEmpty()) e.scope.launch { e.checks.checkMany(fresh) }
        val settings = decoded.settings?.let { SettingsJson.apply(it, e.settings.value) }
        if (settings != null) e.scope.launch { e.saveSettings(settings) }
        return ImportSummary(added, present, skipped, withPins, withFilters, askedForMore, settingsTaken = settings != null)
    }

    private fun hasFilters(config: AppConfig): Boolean = listOf(
        config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
        config.releases.versionExtract, config.assets.include, config.assets.exclude,
        config.releases.versionFilter, config.assets.innerFilter,
    ).any { !it.isNullOrBlank() }

    suspend fun exportTo(uri: Uri, format: ExportFormat = ExportFormat.TERN): Int = withContext(Dispatchers.IO) {
        val (text, count) = export(null, format)
        val out = e.context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write to $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        count
    }

    suspend fun exportToFolder(format: ExportFormat): SavedFile = runInterruptible(Dispatchers.IO) {
        files.save(export(null, format).first, prefix = ExportNames.prefixOf(format))
    }

    /** A fresh file in the share folder, which holds nothing older, and its content address. */
    suspend fun shareable(appIds: Collection<String>?, format: ExportFormat): Uri = runInterruptible(Dispatchers.IO) {
        val text = export(appIds, format).first
        val dir = File(e.context.cacheDir, SHARE_FOLDER)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot write to $dir")
        val name = if (format == ExportFormat.OBTAINIUM) "obtainium-export.json" else "tern-apps.json"
        val file = File(dir, name).apply { writeText(text) }
        FileProvider.getUriForFile(e.context, OtherAppInstaller.authority(e.context), file)
    }

    /**
     * The apps in [appIds], or all of them, and how many were written. The settings say whether
     * apps that are not installed stay out, and whether the export carries the settings too.
     */
    private fun export(appIds: Collection<String>?, format: ExportFormat): Pair<String, Int> {
        val s = e.settings.value
        val configs = e.stored.values.map { it.config }
            .filter { appIds == null || it.id in appIds }
            .filter { !s.exportInstalledOnly || e.readInstalled(e.packageOf(it)) != null }
            .sortedBy { it.name.lowercase() }
        return when (format) {
            ExportFormat.TERN -> TernExport.write(configs, e.nowMs(), BuildConfig.VERSION_NAME, SettingsJson.encode(s).takeIf { s.exportSettings }) to configs.size
            ExportFormat.OBTAINIUM -> ObtainiumExport.write(configs, e.nowMs(), BuildConfig.VERSION_NAME, SettingsJson.encode(s).takeIf { s.exportSettings })
                .let { it.text to it.written }
        }
    }

    private fun read(uri: Uri): ByteArray = try {
        val input = e.context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        input.use { Capped.read(it, MAX_BYTES) }
    } catch (_: Capped.TooLarge) {
        throw ProblemException(Problem(ProblemKind.UNSUPPORTED, e.texts.importTooLarge(MAX_BYTES)))
    } catch (ex: IOException) {
        throw ProblemException(Problem(ProblemKind.STORAGE, e.texts.unreadableFile(ex.message)))
    } catch (ex: SecurityException) {
        throw ProblemException(Problem(ProblemKind.STORAGE, e.texts.unreadableFile(ex.message)))
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_APPS = 2000
        const val SHARE_FOLDER = "share"
    }
}

/** A file name MediaStore takes: no folders, nothing unseen, not too long. */
internal fun savedName(name: String): String {
    val plain = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").trim().trimStart('.')
    return plain.take(120).ifEmpty { "download.bin" }
}

/** What kind of file [name] is, as Android's Downloads lists it. */
internal fun mimeOf(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".apk") -> "application/vnd.android.package-archive"
        lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") || lower.endsWith(".zip") -> "application/zip"
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "application/gzip"
        lower.endsWith(".tar") -> "application/x-tar"
        else -> "application/octet-stream"
    }
}
