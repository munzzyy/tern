package io.github.munzzyy.stamp.core.interop

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonArray
import io.github.munzzyy.stamp.core.json.JsonBool
import io.github.munzzyy.stamp.core.json.JsonNumber
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.json.JsonString
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.AssetPolicy
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.model.UpdateMode
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.source.forge.ForgejoSource
import io.github.munzzyy.stamp.core.source.forge.GitHubSource
import io.github.munzzyy.stamp.core.source.forge.GitLabSource
import io.github.munzzyy.stamp.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.stamp.core.source.fdroid.FDroidSource
import io.github.munzzyy.stamp.core.source.web.DirectSource
import io.github.munzzyy.stamp.core.source.web.JenkinsSource
import io.github.munzzyy.stamp.core.source.web.SourceForgeSource
import io.github.munzzyy.stamp.core.source.web.SourceHutSource
import io.github.munzzyy.stamp.core.verify.Fingerprints

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
                val reason = if (Urls.normalize(url) == null) "Its address is not a web address Stamp can open" else unsupportedReason(overrideSource)
                skipped.add(Skipped(name.take(MAX_NAME), url.take(MAX_NAME), reason))
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
        return ImportResult(apps, skipped)
    }

    private fun mapSource(url: String, overrideSource: String?, settings: JsonObject): SourceSpec? {
        if (overrideSource in UNSUPPORTED_SOURCES) return null
        val address = Urls.normalize(url) ?: return null
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
        if (Urls.host(address).removePrefix("www.") in UNSUPPORTED_HOSTS) return null
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

    private fun unsupportedReason(overrideSource: String?): String = when (overrideSource) {
        "APKPure" -> "APKPure is not supported by Stamp; find the developer's own release page instead"
        "APKMirror" -> "APKMirror is not supported by Stamp (its maintainers block direct downloads)"
        "APKCombo" -> "APKCombo is not supported by Stamp"
        "Aptoide" -> "Aptoide is not supported by Stamp"
        "Uptodown" -> "Uptodown is not supported by Stamp"
        "HuaweiAppGallery" -> "Huawei AppGallery is not supported by Stamp"
        "SamsungGalaxyStore" -> "Samsung Galaxy Store is not supported by Stamp"
        "VivoAppStore" -> "Vivo App Store is not supported by Stamp"
        "Tencent" -> "Tencent My App is not supported by Stamp"
        "CoolApk" -> "CoolApk is not supported by Stamp"
        "RuStore" -> "RuStore is not supported by Stamp"
        "RockMods" -> "RockMods is not supported by Stamp"
        "LiteAPKs" -> "LiteAPKs is not supported by Stamp"
        "NeutronCode" -> "NeutronCode is not supported by Stamp"
        "Apk4Free" -> "Apk4Free is not supported by Stamp"
        "Farsroid" -> "Farsroid is not supported by Stamp"
        "TelegramApp" -> "Telegram is not supported by Stamp"
        "ItchIO" -> "itch.io is not supported by Stamp"
        else -> "This source is not supported by Stamp"
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

    private val UNSUPPORTED_SOURCES = setOf(
        "APKPure", "APKMirror", "APKCombo", "Aptoide", "Uptodown", "HuaweiAppGallery", "SamsungGalaxyStore",
        "VivoAppStore", "Tencent", "CoolApk", "RuStore", "RockMods", "LiteAPKs", "NeutronCode", "Apk4Free",
        "Farsroid", "TelegramApp", "ItchIO",
    )

    private val UNSUPPORTED_HOSTS = setOf(
        "apkpure.com", "apkpure.net", "apkmirror.com", "apkcombo.com", "aptoide.com", "uptodown.com",
        "telegram.org", "itch.io",
    )
}
