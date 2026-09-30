package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.AppConfig

class TernExportException(message: String) : Exception(message)

/** What an export file holds: the apps, and the settings when the person chose to include them. */
data class TernExportFile(val apps: List<AppConfig>, val settings: JsonObject?)

object TernExport {
    const val SCHEMA = 1
    private const val FORMAT = "tern-export"

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
        val configs = apps.objects().map { obj ->
            try {
                shown(AppConfigJson.decode(obj))
            } catch (e: AppConfigJsonException) {
                throw TernExportException("Invalid app entry: ${e.message}")
            }
        }
        return TernExportFile(configs, root.obj("settings"))
    }
}
