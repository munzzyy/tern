package io.github.munzzyy.jackdaw.core.interop

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonObject
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.AssetPolicy
import io.github.munzzyy.jackdaw.core.model.ReleasePolicy
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.model.UpdateMode

class AppConfigJsonException(message: String) : Exception(message)

object AppConfigJson {
    const val SCHEMA = 1

    fun encode(config: AppConfig): JsonObject = Json.obj(
        "schema" to SCHEMA,
        "id" to config.id,
        "source" to Json.obj("type" to config.source.type, "url" to config.source.url, "options" to config.source.options),
        "name" to config.name,
        "author" to config.author,
        "packageName" to config.packageName,
        "releases" to Json.obj(
            "includePrereleases" to config.releases.includePrereleases,
            "tagFilter" to config.releases.tagFilter,
            "titleFilter" to config.releases.titleFilter,
            "notesFilter" to config.releases.notesFilter,
            "versionExtract" to config.releases.versionExtract,
            "minAgeDays" to config.releases.minAgeDays,
            "skippedReleaseId" to config.releases.skippedReleaseId,
            "fallbackToOlder" to config.releases.fallbackToOlder,
        ),
        "assets" to Json.obj(
            "include" to config.assets.include,
            "exclude" to config.assets.exclude,
            "matchDevice" to config.assets.matchDevice,
        ),
        "updates" to config.updates.name,
        "trackOnly" to config.trackOnly,
        "pinnedSigners" to config.pinnedSigners,
        "categories" to config.categories,
        "favorite" to config.favorite,
        "notes" to config.notes,
    )

    fun decode(obj: JsonObject): AppConfig {
        val id = obj.string("id") ?: throw AppConfigJsonException("Missing id")
        val sourceObj = obj.obj("source") ?: throw AppConfigJsonException("Missing source")
        val type = sourceObj.string("type") ?: throw AppConfigJsonException("Missing source.type")
        val url = sourceObj.string("url") ?: throw AppConfigJsonException("Missing source.url")
        val options = sourceObj.obj("options")?.fields?.mapValues { (_, v) -> stringValue(v) ?: "" } ?: emptyMap()
        val name = obj.string("name") ?: throw AppConfigJsonException("Missing name")

        val releasesObj = obj.obj("releases")
        val releases = ReleasePolicy(
            includePrereleases = releasesObj?.bool("includePrereleases") ?: false,
            tagFilter = releasesObj?.string("tagFilter"),
            titleFilter = releasesObj?.string("titleFilter"),
            notesFilter = releasesObj?.string("notesFilter"),
            versionExtract = releasesObj?.string("versionExtract"),
            minAgeDays = releasesObj?.long("minAgeDays")?.toInt() ?: 0,
            skippedReleaseId = releasesObj?.string("skippedReleaseId"),
            fallbackToOlder = releasesObj?.bool("fallbackToOlder") ?: true,
        )

        val assetsObj = obj.obj("assets")
        val assets = AssetPolicy(
            include = assetsObj?.string("include"),
            exclude = assetsObj?.string("exclude"),
            matchDevice = assetsObj?.bool("matchDevice") ?: true,
        )

        val updates = obj.string("updates")?.let { raw ->
            runCatching { UpdateMode.valueOf(raw) }.getOrElse { throw AppConfigJsonException("Unknown update mode $raw") }
        } ?: UpdateMode.NOTIFY

        return AppConfig(
            id = id,
            source = SourceSpec(type, url, options),
            name = name,
            author = obj.string("author"),
            packageName = obj.string("packageName"),
            releases = releases,
            assets = assets,
            updates = updates,
            trackOnly = obj.bool("trackOnly") ?: false,
            pinnedSigners = obj.array("pinnedSigners")?.strings() ?: emptyList(),
            categories = obj.array("categories")?.strings() ?: emptyList(),
            favorite = obj.bool("favorite") ?: false,
            notes = obj.string("notes"),
        )
    }

    private fun stringValue(value: io.github.munzzyy.jackdaw.core.json.JsonValue): String? = when (value) {
        is io.github.munzzyy.jackdaw.core.json.JsonString -> value.value
        is io.github.munzzyy.jackdaw.core.json.JsonNumber -> value.raw
        is io.github.munzzyy.jackdaw.core.json.JsonBool -> value.value.toString()
        else -> null
    }
}
