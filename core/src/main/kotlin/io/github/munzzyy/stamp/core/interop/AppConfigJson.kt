package io.github.munzzyy.stamp.core.interop

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.AssetPolicy
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.apk.BinaryManifest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.verify.Fingerprints

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
        val id = short(obj.string("id"), "id") ?: throw AppConfigJsonException("Missing id")
        val sourceObj = obj.obj("source") ?: throw AppConfigJsonException("Missing source")
        val type = sourceObj.string("type") ?: throw AppConfigJsonException("Missing source.type")
        if (type !in KNOWN_TYPES) throw AppConfigJsonException("Unknown source type ${type.take(40)}")
        val url = sourceObj.string("url")?.let(Urls::normalize) ?: throw AppConfigJsonException("source.url is not a web address")
        val options = sourceObj.obj("options")?.fields.orEmpty()
            .entries.take(MAX_OPTIONS)
            .associate { (k, v) -> k.take(MAX_SHORT) to (stringValue(v) ?: "").take(MAX_LONG) }
        val name = short(obj.string("name"), "name") ?: throw AppConfigJsonException("Missing name")
        val packageName = obj.string("packageName")
        if (packageName != null && !BinaryManifest.isValidName(packageName)) throw AppConfigJsonException("packageName is not a package name")

        val releasesObj = obj.obj("releases")
        val releases = ReleasePolicy(
            includePrereleases = releasesObj?.bool("includePrereleases") ?: false,
            tagFilter = releasesObj?.string("tagFilter"),
            titleFilter = releasesObj?.string("titleFilter"),
            notesFilter = releasesObj?.string("notesFilter"),
            versionExtract = releasesObj?.string("versionExtract"),
            minAgeDays = (releasesObj?.long("minAgeDays") ?: 0L).coerceIn(0L, 365L).toInt(),
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
            author = short(obj.string("author"), "author"),
            packageName = packageName,
            releases = releases,
            assets = assets,
            updates = updates,
            trackOnly = obj.bool("trackOnly") ?: false,
            pinnedSigners = obj.array("pinnedSigners")?.strings().orEmpty().mapNotNull(Fingerprints::normalize).distinct().take(MAX_OPTIONS),
            categories = obj.array("categories")?.strings().orEmpty().map { it.take(MAX_SHORT) }.take(MAX_OPTIONS),
            favorite = obj.bool("favorite") ?: false,
            notes = obj.string("notes")?.take(MAX_LONG),
        )
    }

    private fun short(value: String?, field: String): String? {
        if (value != null && value.length > MAX_SHORT) throw AppConfigJsonException("$field is longer than $MAX_SHORT characters")
        return value
    }

    private const val MAX_SHORT = 200
    private const val MAX_LONG = 4000
    private const val MAX_OPTIONS = 32
    private val KNOWN_TYPES = setOf(
        SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS, SourceTypes.GITLAB, SourceTypes.FORGEJO, SourceTypes.FDROID,
        SourceTypes.FDROID_REPO, SourceTypes.HTML, SourceTypes.DIRECT, SourceTypes.JENKINS, SourceTypes.SOURCEHUT, SourceTypes.SOURCEFORGE,
    )

    private fun stringValue(value: io.github.munzzyy.stamp.core.json.JsonValue): String? = when (value) {
        is io.github.munzzyy.stamp.core.json.JsonString -> value.value
        is io.github.munzzyy.stamp.core.json.JsonNumber -> value.raw
        is io.github.munzzyy.stamp.core.json.JsonBool -> value.value.toString()
        else -> null
    }
}
