package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.forge.ForgejoSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.source.forge.GitLabSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.web.DirectSource
import io.github.munzzyy.tern.core.source.web.JenkinsSource
import io.github.munzzyy.tern.core.source.web.SourceForgeSource
import io.github.munzzyy.tern.core.source.web.SourceHutSource
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.core.verify.Fingerprints

class ObtainiumImportException(message: String) : Exception(message)

data class Skipped(val name: String, val url: String, val reason: String)

data class ImportResult(val apps: List<AppConfig>, val skipped: List<Skipped>)

object ObtainiumImport {
    fun read(text: String): ImportResult {
        val root = try {
            Json.parse(text)
        } catch (_: Exception) {
            throw ObtainiumImportException("Not valid JSON")
        }
        val appsArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("apps") ?: throw ObtainiumImportException("Missing apps array")
            else -> throw ObtainiumImportException("Unexpected export shape")
        }

        val apps = ArrayList<AppConfig>()
        val skipped = ArrayList<Skipped>()
        if (appsArray.size > MAX_APPS) throw ObtainiumImportException("More than $MAX_APPS apps in one file")
        for (entry in appsArray.objects()) {
            val url = entry.string("url") ?: continue
            val name = (entry.string("name")?.takeIf { it.isNotBlank() } ?: url).take(MAX_NAME)
            val overrideSource = entry.string("overrideSource")
            val settings = entry.obj("additionalSettings")
                ?: entry.string("additionalSettings")?.let { raw -> runCatching { Json.parseObject(raw) }.getOrNull() }
                ?: JsonObject(emptyMap())

            val source = mapSource(url, overrideSource, settings)
            if (source == null) {
                val reason = if (Urls.normalize(url) == null) "Its address is not a web address Tern can open" else unsupportedReason(overrideSource)
                skipped.add(Skipped(Shown.line(name, MAX_NAME), Shown.line(url, MAX_NAME), reason))
                continue
            }

            val id = entry.string("id")?.takeIf { FDroidSource.isValidPackage(it) }
            val categories = entry.array("categories")?.strings()
                ?: entry.string("category")?.let { listOf(it) }
                ?: emptyList()
            val pinned = entry.bool("pinned") ?: false

            val invertApkFilter = settingBool(settings, "invertAPKFilter") ?: false
            val apkFilter = settingString(settings, "apkFilterRegEx")
            val pinnedSigners = settingString(settings, "allowedSigningCertHashes")
                ?.split(Regex("[\\n,]"))
                ?.mapNotNull { Fingerprints.normalize(it) }
                ?: emptyList()

            apps.add(
                AppConfig(
                    id = id ?: source.url,
                    source = source,
                    name = name,
                    author = entry.string("author")?.takeIf { it.isNotBlank() }?.take(MAX_NAME),
                    packageName = id,
                    releases = ReleasePolicy(
                        includePrereleases = settingBool(settings, "includePrereleases") ?: false,
                        titleFilter = settingString(settings, "filterReleaseTitlesByRegEx"),
                        notesFilter = settingString(settings, "filterReleaseNotesByRegEx"),
                        versionExtract = settingString(settings, "versionExtractionRegEx"),
                        minAgeDays = (settingInt(settings, "minimumUpdateAgeDays") ?: 0).coerceIn(0, 365),
                        fallbackToOlder = settingBool(settings, "fallbackToOlderReleases") ?: true,
                    ),
                    assets = AssetPolicy(
                        include = if (invertApkFilter) null else apkFilter,
                        exclude = if (invertApkFilter) apkFilter else null,
                        matchDevice = settingBool(settings, "autoApkFilterByArch") ?: true,
                    ),
                    updates = if (settingBool(settings, "exemptFromBackgroundUpdates") == true) UpdateMode.MANUAL else UpdateMode.NOTIFY,
                    trackOnly = settingBool(settings, "trackOnly") ?: false,
                    pinnedSigners = pinnedSigners,
                    categories = categories.map { it.take(MAX_NAME) }.take(32),
                    favorite = pinned,
                    notes = settingString(settings, "about")?.take(4000),
                ),
            )
        }
        return ImportResult(apps.map(::shown), skipped)
    }

    private fun mapSource(url: String, overrideSource: String?, settings: JsonObject): SourceSpec? {
        val address = Urls.normalize(url) ?: return null
        STORE_SOURCES[overrideSource]?.let { type -> return store(type, address, settings) }
        return when (overrideSource) {
            "GitHub" -> GitHubSource().match(address)
            "GitLab" -> GitLabSource().match(address) ?: repository(SourceTypes.GITLAB, address, minSegments = 2, maxSegments = 12)
            "Codeberg" -> ForgejoSource().match(address) ?: repository(SourceTypes.FORGEJO, address, minSegments = 2, maxSegments = 2)
            "FDroid", "IzzyOnDroid" -> FDroidSource().match(address)
            "FDroidRepo" -> repositoryApp(address, settings)
            "HTML" -> html(address, settings)
            "DirectAPKLink" -> SourceSpec(SourceTypes.DIRECT, address)
            "Jenkins" -> JenkinsSource().match(address)
            "SourceHut" -> SourceHutSource().match(address)
            "SourceForge" -> SourceForgeSource().match(address)
            null -> matchByUrl(address, settings)
            else -> null
        }
    }

    /** A self-hosted forge the user told Obtainium about by hand: keep the host, cut the path to the project. */
    private fun repository(type: String, address: String, minSegments: Int, maxSegments: Int): SourceSpec? {
        val segments = Urls.segments(address).takeWhile { it != "-" }.take(maxSegments)
        if (segments.size < minSegments) return null
        val last = segments.last().removeSuffix(".git")
        val path = (segments.dropLast(1) + last).joinToString("/") { Urls.encodeSegment(it) }
        val uri = Urls.parseHttps(address) ?: return null
        return SourceSpec(type, "https://${uri.authority}/$path")
    }

    private fun repositoryApp(address: String, settings: JsonObject): SourceSpec? {
        val spec = FDroidRepoSource().match(address) ?: return null
        val app = settingString(settings, "appIdOrName") ?: Urls.queryParam(address, "appId")
        return if (app == null) spec else spec.copy(options = spec.options + (SourceOptions.PACKAGE to app))
    }

    private fun html(address: String, settings: JsonObject): SourceSpec {
        val options = LinkedHashMap<String, String>()
        settingString(settings, "customLinkFilterRegex")?.let { options[SourceOptions.LINK_FILTER] = it }
        val steps = settings.array("intermediateLink")?.objects().orEmpty()
            .mapNotNull { settingString(it, "customLinkFilterRegex") }
            .take(MAX_STEPS)
        if (steps.isNotEmpty()) options[SourceOptions.STEPS] = Json.write(Json.of(steps))
        if (settingBool(settings, "skipSort") == true) options[SourceOptions.SORT] = "page"
        if (settingBool(settings, "filterByLinkText") == true) options[SourceOptions.VERSION_FROM] = "text"
        if (settingBool(settings, "versionExtractWholePage") == true) options[SourceOptions.VERSION_FROM] = "page"
        return SourceSpec(SourceTypes.HTML, address, options)
    }

    private fun matchByUrl(address: String, settings: JsonObject): SourceSpec? {
        for (type in STORE_SOURCES.values) store(type, address, settings)?.let { return it }
        GitHubSource().match(address)?.let { return it }
        GitLabSource().match(address)?.let { return it }
        ForgejoSource().match(address)?.let { return it }
        FDroidSource().match(address)?.let { return it }
        if (FDroidRepoSource().match(address) != null) return repositoryApp(address, settings)
        JenkinsSource().match(address)?.let { return it }
        SourceHutSource().match(address)?.let { return it }
        SourceForgeSource().match(address)?.let { return it }
        DirectSource().match(address)?.let { return it }
        return html(address, settings)
    }

    private fun unsupportedReason(overrideSource: String?): String {
        val type = STORE_SOURCES[overrideSource] ?: return "This source is not supported by Tern"
        val name = SourceTypes.displayName(type) ?: overrideSource
        return "Tern could not read this address as an app on $name"
    }

    /** A store source by its type, with the options Obtainium keeps for it that Tern reads too. */
    private fun store(type: String, address: String, settings: JsonObject): SourceSpec? {
        val spec = REGISTRY.get(type)?.match(address) ?: return null
        if (type != SourceTypes.SAMSUNG) return spec
        val options = LinkedHashMap(spec.options)
        settingString(settings, "deviceId")?.let { options[SourceOptions.DEVICE_MODEL] = it.take(40) }
        settingString(settings, "csc")?.let { options[SourceOptions.CSC] = it.take(10) }
        return spec.copy(options = options)
    }

    private fun settingString(settings: JsonObject, key: String): String? = when (val v = settings[key]) {
        is JsonString -> v.value.takeIf { it.isNotEmpty() }
        is JsonNumber -> v.raw
        is JsonBool -> v.value.toString()
        else -> null
    }

    private fun settingBool(settings: JsonObject, key: String): Boolean? = when (val v = settings[key]) {
        is JsonBool -> v.value
        is JsonString -> v.value.toBooleanStrictOrNull()
        is JsonNumber -> v.toLongOrNull()?.let { it != 0L }
        else -> null
    }

    private fun settingInt(settings: JsonObject, key: String): Int? = when (val v = settings[key]) {
        is JsonNumber -> v.toLongOrNull()?.toInt()
        is JsonString -> v.value.toIntOrNull()
        else -> null
    }

    private const val MAX_APPS = 5000
    private const val MAX_NAME = 200
    private const val MAX_STEPS = 5

    /** Obtainium's names for the stores and single-app sites, as its exports write them. */
    private val STORE_SOURCES = linkedMapOf(
        "HuaweiAppGallery" to SourceTypes.HUAWEI,
        "SamsungGalaxyStore" to SourceTypes.SAMSUNG,
        "VivoAppStore" to SourceTypes.VIVO,
        "Tencent" to SourceTypes.TENCENT,
        "RuStore" to SourceTypes.RUSTORE,
        "CoolApk" to SourceTypes.COOLAPK,
        "ItchIO" to SourceTypes.ITCHIO,
        "TelegramApp" to SourceTypes.TELEGRAM,
        "NeutronCode" to SourceTypes.NEUTRONCODE,
        "APKPure" to SourceTypes.APKPURE,
        "Aptoide" to SourceTypes.APTOIDE,
        "Uptodown" to SourceTypes.UPTODOWN,
        "APKCombo" to SourceTypes.APKCOMBO,
        "APKMirror" to SourceTypes.APKMIRROR,
        "Farsroid" to SourceTypes.FARSROID,
        "LiteAPKs" to SourceTypes.LITEAPKS,
        "Apk4Free" to SourceTypes.APK4FREE,
        "RockMods" to SourceTypes.ROCKMODS,
    )

    private val REGISTRY = SourceRegistry.standard()
}
