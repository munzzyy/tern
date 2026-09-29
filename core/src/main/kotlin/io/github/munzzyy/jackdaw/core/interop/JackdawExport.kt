package io.github.munzzyy.jackdaw.core.interop

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonArray
import io.github.munzzyy.jackdaw.core.model.AppConfig

class JackdawExportException(message: String) : Exception(message)

object JackdawExport {
    const val SCHEMA = 1
    private const val FORMAT = "jackdaw-export"

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
            throw JackdawExportException("Not a valid Jackdaw export")
        }
        if (root.string("format") != FORMAT) throw JackdawExportException("Not a Jackdaw export")
        val schema = root.long("schema") ?: throw JackdawExportException("Missing schema version")
        if (schema != SCHEMA.toLong()) throw JackdawExportException("Unsupported export schema $schema")
        val apps = root.array("apps") ?: throw JackdawExportException("Missing apps array")
        return apps.objects().map { obj ->
            try {
                AppConfigJson.decode(obj)
            } catch (e: AppConfigJsonException) {
                throw JackdawExportException("Invalid app entry: ${e.message}")
            }
        }
    }
}
