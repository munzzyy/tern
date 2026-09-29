package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.model.AppConfig

class TernExportException(message: String) : Exception(message)

object TernExport {
    const val SCHEMA = 1
    private const val FORMAT = "tern-export"

    fun write(apps: List<AppConfig>, exportedAtMs: Long, appVersion: String): String {
        val obj = Json.obj(
            "format" to FORMAT,
            "schema" to SCHEMA,
            "exportedAt" to exportedAtMs,
            "appVersion" to appVersion,
            "apps" to JsonArray(apps.map { AppConfigJson.encode(it) }),
        )
        return Json.write(obj, indent = true)
    }

    fun read(text: String): List<AppConfig> {
        val root = try {
            Json.parseObject(text)
        } catch (_: Exception) {
            throw TernExportException("Not a valid Tern export")
        }
        if (root.string("format") != FORMAT) throw TernExportException("Not a Tern export")
        val schema = root.long("schema") ?: throw TernExportException("Missing schema version")
        if (schema != SCHEMA.toLong()) throw TernExportException("Unsupported export schema $schema")
        val apps = root.array("apps") ?: throw TernExportException("Missing apps array")
        return apps.objects().map { obj ->
            try {
                shown(AppConfigJson.decode(obj))
            } catch (e: AppConfigJsonException) {
                throw TernExportException("Invalid app entry: ${e.message}")
            }
        }
    }
}
