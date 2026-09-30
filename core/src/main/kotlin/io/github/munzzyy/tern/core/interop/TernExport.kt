package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.source.Refusal
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.text.Shown

class TernExportException(message: String) : Exception(message)

/**
 * What an export file holds: the apps, and the settings when the person chose to include them.
 * [skipped] are the apps of sites Tern no longer reads, which a file from an older build can name.
 */
data class TernExportFile(val apps: List<AppConfig>, val settings: JsonObject?, val skipped: List<Skipped> = emptyList())

object TernExport {
    const val SCHEMA = 1
    private const val FORMAT = "tern-export"
    private const val MAX_NAME = 200

    /** [settings] go in only when given; the file never holds a token, whatever they are. */
    fun write(apps: List<AppConfig>, exportedAtMs: Long, appVersion: String, settings: JsonObject? = null): String {
        val fields = mutableListOf<Pair<String, Any?>>(
            "format" to FORMAT,
            "schema" to SCHEMA,
            "exportedAt" to exportedAtMs,
            "appVersion" to appVersion,
            "apps" to JsonArray(apps.map { AppConfigJson.encode(it) }),
        )
        if (settings != null) fields += "settings" to settings
        return Json.write(Json.obj(*fields.toTypedArray()), indent = true)
    }

    fun read(text: String): List<AppConfig> = readFile(text).apps

    fun readFile(text: String): TernExportFile {
        val root = try {
            Json.parseObject(text)
        } catch (_: Exception) {
            throw TernExportException("Not a valid Tern export")
        }
        if (root.string("format") != FORMAT) throw TernExportException("Not a Tern export")
        val schema = root.long("schema") ?: throw TernExportException("Missing schema version")
        if (schema != SCHEMA.toLong()) throw TernExportException("Unsupported export schema $schema")
        val apps = root.array("apps") ?: throw TernExportException("Missing apps array")
        val configs = ArrayList<AppConfig>()
        val skipped = ArrayList<Skipped>()
        for (obj in apps.objects()) {
            val source = obj.obj("source")
            val url = source?.string("url").orEmpty()
            val refusal = Refusal.ofType(source?.string("type")) ?: Refusal.ofUrl(url)
            if (refusal != null) {
                val name = obj.string("customName")?.takeIf { it.isNotBlank() } ?: obj.string("name")?.takeIf { it.isNotBlank() } ?: url
                skipped += Skipped(Shown.line(name, MAX_NAME), Shown.line(url, MAX_NAME), SourceRegistry.refusalText(refusal), refusal)
                continue
            }
            configs += try {
                shown(AppConfigJson.decode(obj))
            } catch (e: AppConfigJsonException) {
                throw TernExportException("Invalid app entry: ${e.message}")
            }
        }
        return TernExportFile(configs, root.obj("settings"), skipped)
    }
}
