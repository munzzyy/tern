package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.HtmlStep
import io.github.munzzyy.tern.core.source.web.PseudoVersion
import io.github.munzzyy.tern.core.source.web.RequestHeaders
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
        r.versionExtract?.let { extract ->
            settings["versionExtractionRegEx"] = extract
            // Obtainium takes the whole match unless a group is named, so the one Tern takes is named.
            settings["matchGroupToUse"] = r.matchGroup ?: ObtainiumOptions.defaultGroup(extract)
        }
        r.versionFilter?.let { settings["filterVersionsByRegEx"] = it }
        when (r.versionFrom) {
            VersionFrom.DATE -> settings["releaseDateAsVersion"] = true
            VersionFrom.TITLE -> settings["releaseTitleAsVersion"] = true
            VersionFrom.TAG -> Unit
        }
        settings["sortMethodChoice"] = when (r.order) {
            ReleaseOrder.VERSION -> "smartname"
            ReleaseOrder.DATE -> "date"
            ReleaseOrder.SOURCE -> "none"
            ReleaseOrder.NAME -> "name"
        }
        if (r.stayBehind > 0) settings["stayOneVersionBehind"] = true
        // Obtainium reads this as text, and takes '' for its global default.
        settings["minimumUpdateAgeDays"] = r.minAgeDays?.toString() ?: ""
        val a = app.assets
        when {
            a.include != null -> settings["apkFilterRegEx"] = a.include
            a.exclude != null -> {
                settings["apkFilterRegEx"] = a.exclude
                settings["invertAPKFilter"] = true
            }
        }
        settings["autoApkFilterByArch"] = a.matchDevice
        if (a.archives) {
            settings["includeZips"] = true
            settings["includeTarballs"] = true
        }
        a.innerFilter?.let {
            settings["zippedApkFilterRegEx"] = it
            settings["tarballedApkFilterRegEx"] = it
        }
        settings["trackOnly"] = app.trackOnly
        // Only an app that installs by itself is left to Obtainium's background installs. One that is
        // never checked in the background also gets no notification of an update there.
        settings["exemptFromBackgroundUpdates"] = app.updates != UpdateMode.AUTO
        app.customName?.let { settings["appName"] = it }
        app.customAuthor?.let { settings["appAuthor"] = it }
        if (app.muted || app.updates == UpdateMode.MANUAL) settings["skipUpdateNotifications"] = true
        if (app.refreshFirst) settings["refreshBeforeDownload"] = true
        if (app.playInstaller) settings["shizukuPretendToBeGooglePlay"] = true
        if (app.pinnedSigners.isNotEmpty()) {
            settings["allowedSigningCertHashes"] = app.pinnedSigners.joinToString("\n") { Fingerprints.format(it) }
        }
        app.notes?.let { settings["about"] = it }
        var url = app.source.url
        when (app.source.type) {
            SourceTypes.HTML -> {
                html(app, settings)
                web(app, settings)
            }
            SourceTypes.DIRECT -> web(app, settings)
            SourceTypes.GITHUB, SourceTypes.FORGEJO -> {
                if (app.source.option(SourceOptions.VERIFY_LATEST) == "true") settings["verifyLatestTag"] = true
                if (app.source.option(SourceOptions.ASSET_DATE) == "true") settings["useLatestAssetDateAsReleaseDate"] = true
            }
            SourceTypes.FDROID_REPO -> app.source.option(SourceOptions.PACKAGE)?.let { pkg ->
                settings["appIdOrName"] = pkg
                url = "$url?appId=${Urls.encodeSegment(pkg)}"
            }
            SourceTypes.SAMSUNG -> {
                app.source.option(SourceOptions.DEVICE_MODEL)?.let { settings["deviceId"] = it }
                app.source.option(SourceOptions.CSC)?.let { settings["csc"] = it }
            }
            SourceTypes.FARSROID -> if (app.source.flag(SourceOptions.FILE_VERSION)) settings["releaseTitleAsVersion"] = true
        }
        return Json.obj(
            "id" to (app.packageName ?: temporaryId(app.source.url)),
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

    /** Obtainium reads the version from a link's address or the whole page; reading it from what the link says is Tern's own, and stays behind. */
    private fun html(app: AppConfig, settings: MutableMap<String, Any?>) {
        val spec = app.source
        spec.option(SourceOptions.LINK_FILTER)?.let { settings["customLinkFilterRegex"] = it }
        val steps = HtmlStep.parse(spec.option(SourceOptions.STEPS)).orEmpty().filter { it.filter.isNotBlank() }.map(::step)
        if (steps.isNotEmpty()) settings["intermediateLink"] = steps
        if (spec.option(SourceOptions.SORT) == "page") settings["skipSort"] = true
        if (spec.flag(SourceOptions.LINK_TEXT)) settings["filterByLinkText"] = true
        if (spec.option(SourceOptions.VERSION_FROM) == "page") settings["versionExtractWholePage"] = true
        if (spec.option(SourceOptions.FIRST_LINK) == "true") settings["reverseSort"] = true
        if (spec.option(SourceOptions.LAST_SEGMENT) == "true") settings["sortByLastLinkSegment"] = true
        if (spec.option(SourceOptions.ANY_TEXT) == "true") settings["matchLinksOutsideATags"] = true
    }

    /** One of the steps through other pages, with every option Obtainium keeps for it. */
    private fun step(step: HtmlStep): Map<String, Any?> = mapOf(
        "customLinkFilterRegex" to step.filter,
        "filterByLinkText" to step.byText,
        "autoLinkFilterByArch" to step.arch,
        "skipSort" to step.pageOrder,
        "reverseSort" to step.firstLink,
        "sortByLastLinkSegment" to step.lastSegment,
        "matchLinksOutsideATags" to step.anyText,
    )

    /** The request headers that hold no key, and the way to tell files apart that the HTML and direct link sources share with Obtainium's. */
    private fun web(app: AppConfig, settings: MutableMap<String, Any?>) {
        val spec = app.source
        RequestHeaders.plainOnly(spec).option(SourceOptions.HEADERS)?.let { raw ->
            val headers = RequestHeaders.parse(raw).map { (name, value) -> mapOf("requestHeader" to "$name: $value") }
            if (headers.isNotEmpty()) settings["requestHeader"] = headers
        }
        when (spec.option(SourceOptions.PSEUDO)) {
            PseudoVersion.HASH.option -> settings["defaultPseudoVersioningMethod"] = "partialAPKHash"
            PseudoVersion.LINK.option -> settings["defaultPseudoVersioningMethod"] = "APKLinkHash"
            PseudoVersion.ETAG.option -> settings["defaultPseudoVersioningMethod"] = "ETag"
        }
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** The id of an app without a package name: twelve hex digits, which Obtainium replaces with the package name at the first install. */
    internal fun temporaryId(url: String): String = Fingerprints.sha256(url.toByteArray()).take(12)

    /**
     * True for an id that names no package: one Obtainium takes for temporary, twelve hex digits or
     * only digits, and the one earlier builds of Tern wrote in its place, tern. and twelve hex digits.
     */
    internal fun isTemporaryId(id: String): Boolean = TEMPORARY_ID.matches(id)

    private val TEMPORARY_ID = Regex("[0-9]+|[0-9a-f]{12}|tern\\.[0-9a-f]{12}")

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
