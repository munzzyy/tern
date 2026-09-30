package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.verify.Fingerprints
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What an Obtainium export of some apps holds, and what could not go into it. */
data class ObtainiumExportResult(val text: String, val written: Int, val left: List<String>)

/**
 * Writes apps in the format Obtainium imports (its export schema 2), so a list can move from Tern
 * to Obtainium as easily as the other way. Each setting goes under the key Obtainium reads it by;
 * what Obtainium has no key for stays behind, and the pinned certificates go as the hashes it
 * blocks on. Sources Obtainium does not read, such as GitHub Actions, are left out and named.
 */
object ObtainiumExport {
    /** [settings] are Tern's portable settings, which go in under Obtainium's names when given. */
    fun write(apps: List<AppConfig>, exportedAtMs: Long, appVersion: String, settings: JsonObject? = null): ObtainiumExportResult {
        val written = ArrayList<JsonValue>()
        val left = ArrayList<String>()
        for (app in apps) {
            val entry = entry(app)
            if (entry == null) left += app.name else written += entry
        }
        val root = Json.obj(
            "schemaVersion" to 2,
            "exportedAt" to iso(exportedAtMs),
            "appVersion" to "tern-$appVersion",
            "apps" to written,
            "settings" to settings?.let(ObtainiumSettings::toObtainium),
        )
        return ObtainiumExportResult(Json.write(root, indent = true), written.size, left)
    }

    /** One app as Obtainium stores it, or null when Obtainium has no source for it. */
    fun entry(app: AppConfig): JsonValue? {
        val source = OVERRIDE[app.source.type] ?: return null
        val override = if (source == "FDroid" && Urls.host(app.source.url).endsWith("izzysoft.de")) "IzzyOnDroid" else source
        val settings = LinkedHashMap<String, Any?>()
        val r = app.releases
        settings["includePrereleases"] = r.includePrereleases
        settings["fallbackToOlderReleases"] = r.fallbackToOlder
        r.titleFilter?.let { settings["filterReleaseTitlesByRegEx"] = it }
        r.notesFilter?.let { settings["filterReleaseNotesByRegEx"] = it }
        r.versionExtract?.let { settings["versionExtractionRegEx"] = it }
        if (r.minAgeDays > 0) settings["minimumUpdateAgeDays"] = r.minAgeDays
        val a = app.assets
        when {
            a.include != null -> settings["apkFilterRegEx"] = a.include
            a.exclude != null -> {
                settings["apkFilterRegEx"] = a.exclude
                settings["invertAPKFilter"] = true
            }
        }
        settings["autoApkFilterByArch"] = a.matchDevice
        settings["trackOnly"] = app.trackOnly
        settings["exemptFromBackgroundUpdates"] = app.updates == UpdateMode.MANUAL
        if (app.pinnedSigners.isNotEmpty()) {
            settings["allowedSigningCertHashes"] = app.pinnedSigners.joinToString("\n") { Fingerprints.format(it) }
        }
        app.notes?.let { settings["about"] = it }
        var url = app.source.url
        when (app.source.type) {
            SourceTypes.HTML -> html(app, settings)
            SourceTypes.FDROID_REPO -> app.source.option(SourceOptions.PACKAGE)?.let { pkg ->
                settings["appIdOrName"] = pkg
                url = "$url?appId=${Urls.encodeSegment(pkg)}"
            }
            SourceTypes.SAMSUNG -> {
                app.source.option(SourceOptions.DEVICE_MODEL)?.let { settings["deviceId"] = it }
                app.source.option(SourceOptions.CSC)?.let { settings["csc"] = it }
            }
        }
        return Json.obj(
            "id" to (app.packageName ?: "tern." + Fingerprints.sha256(app.source.url.toByteArray()).take(12)),
            "url" to url,
            "author" to (app.author ?: ""),
            "name" to app.name,
            "installedVersion" to null,
            "latestVersion" to null,
            "apkUrls" to "[]",
            "otherAssetUrls" to "[]",
            "preferredApkIndex" to 0,
            "additionalSettings" to Json.write(Json.of(settings)),
            "lastUpdateCheck" to null,
            "pinned" to app.favorite,
            "categories" to app.categories,
            "releaseDate" to null,
            "changeLog" to null,
            "releaseUrl" to null,
            "overrideSource" to override,
            "allowIdChange" to (app.packageName == null),
        )
    }

    private fun html(app: AppConfig, settings: MutableMap<String, Any?>) {
        val spec = app.source
        spec.option(SourceOptions.LINK_FILTER)?.let { settings["customLinkFilterRegex"] = it }
        spec.option(SourceOptions.STEPS)?.let { raw ->
            val steps = runCatching { Json.parseArray(raw).strings() }.getOrDefault(emptyList())
            if (steps.isNotEmpty()) settings["intermediateLink"] = steps.map { mapOf("customLinkFilterRegex" to it) }
        }
        if (spec.option(SourceOptions.SORT) == "page") settings["skipSort"] = true
        when (spec.option(SourceOptions.VERSION_FROM)) {
            "text" -> settings["filterByLinkText"] = true
            "page" -> settings["versionExtractWholePage"] = true
        }
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Obtainium's own names for the sources, as its exports write them under overrideSource. */
    private val OVERRIDE = mapOf(
        SourceTypes.GITHUB to "GitHub",
        SourceTypes.GITLAB to "GitLab",
        SourceTypes.FORGEJO to "Codeberg",
        SourceTypes.FDROID to "FDroid",
        SourceTypes.FDROID_REPO to "FDroidRepo",
        SourceTypes.HTML to "HTML",
        SourceTypes.DIRECT to "DirectAPKLink",
        SourceTypes.JENKINS to "Jenkins",
        SourceTypes.SOURCEHUT to "SourceHut",
        SourceTypes.SOURCEFORGE to "SourceForge",
        SourceTypes.HUAWEI to "HuaweiAppGallery",
        SourceTypes.SAMSUNG to "SamsungGalaxyStore",
        SourceTypes.VIVO to "VivoAppStore",
        SourceTypes.TENCENT to "Tencent",
        SourceTypes.RUSTORE to "RuStore",
        SourceTypes.COOLAPK to "CoolApk",
        SourceTypes.ITCHIO to "ItchIO",
        SourceTypes.TELEGRAM to "TelegramApp",
        SourceTypes.NEUTRONCODE to "NeutronCode",
        SourceTypes.APKPURE to "APKPure",
        SourceTypes.APTOIDE to "Aptoide",
        SourceTypes.UPTODOWN to "Uptodown",
        SourceTypes.APKCOMBO to "APKCombo",
        SourceTypes.APKMIRROR to "APKMirror",
        SourceTypes.FARSROID to "Farsroid",
        SourceTypes.LITEAPKS to "LiteAPKs",
        SourceTypes.APK4FREE to "Apk4Free",
        SourceTypes.ROCKMODS to "RockMods",
    )
}
