package io.github.munzzyy.stamp.core.interop

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonArray
import io.github.munzzyy.stamp.core.model.AppConfig

class StampExportException(message: String) : Exception(message)

object StampExport {
    const val SCHEMA = 1
    private const val FORMAT = "stamp-export"

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
            throw StampExportException("Not a valid Stamp export")
        }
        if (root.string("format") != FORMAT) throw StampExportException("Not a Stamp export")
        val schema = root.long("schema") ?: throw StampExportException("Missing schema version")
        if (schema != SCHEMA.toLong()) throw StampExportException("Unsupported export schema $schema")
        val apps = root.array("apps") ?: throw StampExportException("Missing apps array")
        return apps.objects().map { obj ->
            try {
                AppConfigJson.decode(obj)
            } catch (e: AppConfigJsonException) {
                throw StampExportException("Invalid app entry: ${e.message}")
            }
        }
    }
}
