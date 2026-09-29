package io.github.munzzyy.jackdaw.core.interop

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonArray
import io.github.munzzyy.jackdaw.core.json.JsonBool
import io.github.munzzyy.jackdaw.core.json.JsonNumber
import io.github.munzzyy.jackdaw.core.json.JsonObject
import io.github.munzzyy.jackdaw.core.json.JsonString
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.AssetPolicy
import io.github.munzzyy.jackdaw.core.model.ReleasePolicy
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidSource
import io.github.munzzyy.jackdaw.core.source.web.DirectSource
import io.github.munzzyy.jackdaw.core.source.web.JenkinsSource
import io.github.munzzyy.jackdaw.core.source.web.SourceForgeSource
import io.github.munzzyy.jackdaw.core.source.web.SourceHutSource
import io.github.munzzyy.jackdaw.core.verify.Fingerprints

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
        for (entry in appsArray.objects()) {
            val url = entry.string("url") ?: continue
            val name = entry.string("name")?.takeIf { it.isNotEmpty() } ?: url
            val overrideSource = entry.string("overrideSource")
            val settings = entry.string("additionalSettings")?.let { raw -> runCatching { Json.parseObject(raw) }.getOrNull() }
                ?: JsonObject(emptyMap())

            val source = mapSource(url, overrideSource, settings)
            if (source == null) {
                skipped.add(Skipped(name, url, unsupportedReason(overrideSource)))
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
                    id = id ?: url,
                    source = source,
                    name = name,
                    author = entry.string("author")?.takeIf { it.isNotEmpty() },
                    packageName = id,
                    releases = ReleasePolicy(
                        includePrereleases = settingBool(settings, "includePrereleases") ?: false,
                        titleFilter = settingString(settings, "filterReleaseTitlesByRegEx"),
                        notesFilter = settingString(settings, "filterReleaseNotesByRegEx"),
                        versionExtract = settingString(settings, "versionExtractionRegEx"),
                        minAgeDays = settingInt(settings, "minimumUpdateAgeDays") ?: 0,
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
                    categories = categories,
                    favorite = pinned,
                    notes = settingString(settings, "about"),
                ),
            )
        }
        return ImportResult(apps, skipped)
    }

    private fun mapSource(url: String, overrideSource: String?, settings: JsonObject): SourceSpec? = when (overrideSource) {
        "GitHub" -> SourceSpec(SourceTypes.GITHUB, url)
        "GitLab" -> SourceSpec(SourceTypes.GITLAB, url)
        "Codeberg" -> SourceSpec(SourceTypes.FORGEJO, url)
        "FDroid" -> SourceSpec(SourceTypes.FDROID, url)
        "IzzyOnDroid" -> SourceSpec(SourceTypes.FDROID, url)
        "FDroidRepo" -> {
            val appIdOrName = settingString(settings, "appIdOrName")
            SourceSpec(SourceTypes.FDROID_REPO, url, buildMap { if (appIdOrName != null) put(SourceOptions.PACKAGE, appIdOrName) })
        }
        "HTML" -> {
            val filter = settingString(settings, "customLinkFilterRegex")
            SourceSpec(SourceTypes.HTML, url, buildMap { if (filter != null) put(SourceOptions.LINK_FILTER, filter) })
        }
        "DirectAPKLink" -> SourceSpec(SourceTypes.DIRECT, url)
        "Jenkins" -> SourceSpec(SourceTypes.JENKINS, url)
        "SourceHut" -> SourceSpec(SourceTypes.SOURCEHUT, url)
        "SourceForge" -> SourceSpec(SourceTypes.SOURCEFORGE, url)
        null -> matchByUrl(url)
        in UNSUPPORTED_SOURCES -> null
        else -> null
    }

    private fun matchByUrl(url: String): SourceSpec? {
        FDroidSource().match(url)?.let { return it }
        FDroidRepoSource().match(url)?.let { return it }
        JenkinsSource().match(url)?.let { return it }
        SourceHutSource().match(url)?.let { return it }
        SourceForgeSource().match(url)?.let { return it }
        DirectSource().match(url)?.let { return it }
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        return when (host) {
            "github.com", "www.github.com" -> SourceSpec(SourceTypes.GITHUB, url)
            "gitlab.com" -> SourceSpec(SourceTypes.GITLAB, url)
            "codeberg.org" -> SourceSpec(SourceTypes.FORGEJO, url)
            else -> if (host != null && host !in UNSUPPORTED_HOSTS) SourceSpec(SourceTypes.HTML, url) else null
        }
    }

    private fun unsupportedReason(overrideSource: String?): String = when (overrideSource) {
        "APKPure" -> "APKPure is not supported by Jackdaw; find the developer's own release page instead"
        "APKMirror" -> "APKMirror is not supported by Jackdaw (its maintainers block direct downloads)"
        "APKCombo" -> "APKCombo is not supported by Jackdaw"
        "Aptoide" -> "Aptoide is not supported by Jackdaw"
        "Uptodown" -> "Uptodown is not supported by Jackdaw"
        "HuaweiAppGallery" -> "Huawei AppGallery is not supported by Jackdaw"
        "SamsungGalaxyStore" -> "Samsung Galaxy Store is not supported by Jackdaw"
        "VivoAppStore" -> "Vivo App Store is not supported by Jackdaw"
        "Tencent" -> "Tencent My App is not supported by Jackdaw"
        "CoolApk" -> "CoolApk is not supported by Jackdaw"
        "RuStore" -> "RuStore is not supported by Jackdaw"
        "RockMods" -> "RockMods is not supported by Jackdaw"
        "LiteAPKs" -> "LiteAPKs is not supported by Jackdaw"
        "NeutronCode" -> "NeutronCode is not supported by Jackdaw"
        "Apk4Free" -> "Apk4Free is not supported by Jackdaw"
        "Farsroid" -> "Farsroid is not supported by Jackdaw"
        "TelegramApp" -> "Telegram is not supported by Jackdaw"
        "ItchIO" -> "itch.io is not supported by Jackdaw"
        else -> "This source is not supported by Jackdaw"
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
