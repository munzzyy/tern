package io.github.munzzyy.jackdaw.engine.real

import android.net.Uri
import io.github.munzzyy.jackdaw.BuildConfig
import io.github.munzzyy.jackdaw.core.interop.JackdawExport
import io.github.munzzyy.jackdaw.core.interop.JackdawExportException
import io.github.munzzyy.jackdaw.core.interop.ObtainiumImport
import io.github.munzzyy.jackdaw.core.interop.ObtainiumImportException
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.data.StoredApp
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.ImportSummary
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class Interop(private val e: RealEngine) {
    suspend fun importFrom(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val text = read(uri)
        val skipped = ArrayList<Pair<String, String>>()
        val configs: List<AppConfig> = try {
            JackdawExport.read(text)
        } catch (jackdaw: JackdawExportException) {
            try {
                val result = ObtainiumImport.read(text)
                result.skipped.forEach { skipped += it.name to it.reason }
                result.apps
            } catch (obtainium: ObtainiumImportException) {
                throw IOException("${jackdaw.message}; ${obtainium.message}")
            }
        }
        var added = 0
        var present = 0
        val fresh = ArrayList<String>()
        val withPins = ArrayList<String>()
        val withFilters = ArrayList<String>()
        for (imported in configs.take(MAX_APPS)) {
            if (e.findBySpec(imported.source) != null) {
                present++
                continue
            }
            val config = try {
                e.validated(imported.copy(id = if (e.stored.containsKey(imported.id)) e.newId() else imported.id))
            } catch (ex: IllegalArgumentException) {
                skipped += imported.name to (ex.message ?: "")
                continue
            }
            e.store.putApp(config, AppState())
            e.stored[config.id] = StoredApp(config, AppState())
            e.event(config.id, EventKind.IMPORTED, e.texts.eventImported())
            fresh += config.id
            added++
            if (config.pinnedSigners.isNotEmpty()) withPins += config.name
            if (hasFilters(config)) withFilters += config.name
        }
        e.publish()
        if (fresh.isNotEmpty()) e.scope.launch { e.checks.checkMany(fresh) }
        ImportSummary(added, present, skipped, withPins, withFilters)
    }

    private fun hasFilters(config: AppConfig): Boolean = listOf(
        config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
        config.releases.versionExtract, config.assets.include, config.assets.exclude,
    ).any { !it.isNullOrBlank() }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val configs = e.stored.values.map { it.config }.sortedBy { it.name.lowercase() }
        val text = JackdawExport.write(configs, e.nowMs(), BuildConfig.VERSION_NAME)
        val out = e.context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write to $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        configs.size
    }

    private fun read(uri: Uri): String {
        val input = e.context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        val out = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("The file is larger than ${MAX_BYTES / 1024 / 1024} MiB")
                out.write(buffer, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_APPS = 2000
    }
}
