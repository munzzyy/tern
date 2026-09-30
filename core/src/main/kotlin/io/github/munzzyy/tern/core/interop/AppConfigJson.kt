package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.core.verify.Fingerprints

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
            "matchGroup" to config.releases.matchGroup,
            "versionFrom" to config.releases.versionFrom.name,
            "order" to config.releases.order.name,
            "stayBehind" to config.releases.stayBehind,
            "versionFilter" to config.releases.versionFilter,
        ),
        "assets" to Json.obj(
            "include" to config.assets.include,
            "exclude" to config.assets.exclude,
            "matchDevice" to config.assets.matchDevice,
            "archives" to config.assets.archives,
            "innerFilter" to config.assets.innerFilter,
        ),
        "updates" to config.updates.name,
        "trackOnly" to config.trackOnly,
        "pinnedSigners" to config.pinnedSigners,
        "categories" to config.categories,
        "favorite" to config.favorite,
        "notes" to config.notes,
        "customName" to config.customName,
        "customAuthor" to config.customAuthor,
        "muted" to config.muted,
        "refreshFirst" to config.refreshFirst,
        "playInstaller" to config.playInstaller,
        "preferredFile" to config.preferredFile,
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
            minAgeDays = releasesObj?.long("minAgeDays")?.coerceIn(0L, 365L)?.toInt(),
            skippedReleaseId = releasesObj?.string("skippedReleaseId"),
            fallbackToOlder = releasesObj?.bool("fallbackToOlder") ?: true,
            matchGroup = short(releasesObj?.string("matchGroup"), "releases.matchGroup"),
            versionFrom = enumOr(releasesObj?.string("versionFrom"), VersionFrom.TAG, "version source"),
            order = enumOr(releasesObj?.string("order"), ReleaseOrder.VERSION, "release order"),
            stayBehind = (releasesObj?.long("stayBehind") ?: 0L).coerceIn(0L, ReleaseSelector.MAX_STAY_BEHIND.toLong()).toInt(),
            versionFilter = releasesObj?.string("versionFilter"),
        )

        val assetsObj = obj.obj("assets")
        val assets = AssetPolicy(
            include = assetsObj?.string("include"),
            exclude = assetsObj?.string("exclude"),
            matchDevice = assetsObj?.bool("matchDevice") ?: true,
            archives = assetsObj?.bool("archives") ?: false,
            innerFilter = assetsObj?.string("innerFilter"),
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
            // What was made or saved before a source became track-only, or by hand, cannot turn that off.
            trackOnly = obj.bool("trackOnly") == true || type in SourceTypes.TRACK_ONLY,
            pinnedSigners = obj.array("pinnedSigners")?.strings().orEmpty().mapNotNull(Fingerprints::normalize).distinct().take(MAX_OPTIONS),
            categories = obj.array("categories")?.strings().orEmpty().map { it.take(MAX_SHORT) }.take(MAX_OPTIONS),
            favorite = obj.bool("favorite") ?: false,
            notes = obj.string("notes")?.take(MAX_LONG),
            customName = short(obj.string("customName"), "customName"),
            customAuthor = short(obj.string("customAuthor"), "customAuthor"),
            muted = obj.bool("muted") ?: false,
            refreshFirst = obj.bool("refreshFirst") ?: false,
            playInstaller = obj.bool("playInstaller") ?: false,
            preferredFile = Shown.lineOrNull(obj.string("preferredFile"), MAX_FILE_NAME),
        )
    }

    private fun short(value: String?, field: String): String? {
        if (value != null && value.length > MAX_SHORT) throw AppConfigJsonException("$field is longer than $MAX_SHORT characters")
        return value
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T, what: String): T {
        if (raw == null) return default
        return enumValues<T>().firstOrNull { it.name == raw } ?: throw AppConfigJsonException("Unknown $what ${raw.take(40)}")
    }

    private const val MAX_SHORT = 200
    private const val MAX_LONG = 4000
    private const val MAX_FILE_NAME = 512
    private const val MAX_OPTIONS = 32
    private val KNOWN_TYPES = SourceTypes.ALL.toSet()

    private fun stringValue(value: io.github.munzzyy.tern.core.json.JsonValue): String? = when (value) {
        is io.github.munzzyy.tern.core.json.JsonString -> value.value
        is io.github.munzzyy.tern.core.json.JsonNumber -> value.raw
        is io.github.munzzyy.tern.core.json.JsonBool -> value.value.toString()
        else -> null
    }
}
