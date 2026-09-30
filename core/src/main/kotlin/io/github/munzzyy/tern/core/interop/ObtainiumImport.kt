package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.Refusal
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.forge.ForgejoSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.source.forge.GitLabSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.web.DirectSource
import io.github.munzzyy.tern.core.source.web.HtmlStep
import io.github.munzzyy.tern.core.source.web.JenkinsSource
import io.github.munzzyy.tern.core.source.web.PseudoVersion
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import io.github.munzzyy.tern.core.source.web.SourceForgeSource
import io.github.munzzyy.tern.core.source.web.SourceHutSource
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.core.verify.Fingerprints

class ObtainiumImportException(message: String) : Exception(message)

/** An app that was not brought over. [refusal] is set when Tern reads nothing from its site at all, and says why in the app's words. */
data class Skipped(val name: String, val url: String, val reason: String, val refusal: Refusal? = null)

/** [settings] are those of the file Tern has a setting for, under Tern's names; null when it carries none. */
data class ImportResult(val apps: List<AppConfig>, val skipped: List<Skipped>, val settings: JsonObject? = null)

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

            val refusal = Refusal.ofObtainium(overrideSource) ?: Refusal.ofUrl(url)
            if (refusal != null) {
                skipped.add(Skipped(Shown.line(name, MAX_NAME), Shown.line(url, MAX_NAME), SourceRegistry.refusalText(refusal), refusal))
                continue
            }
            val source = mapSource(url, overrideSource, settings)?.let { withOptions(it, settings) }
            if (source == null) {
                val reason = if (Urls.normalize(url) == null) "Its address is not a web address Tern can open" else unsupportedReason(overrideSource)
                skipped.add(Skipped(Shown.line(name, MAX_NAME), Shown.line(url, MAX_NAME), reason))
                continue
            }

            // An id that stands in for a package name until the first install names none.
            val id = entry.string("id")?.takeIf { !ObtainiumExport.isTemporaryId(it) && FDroidSource.isValidPackage(it) }
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
            val versionExtract = settingString(settings, "versionExtractionRegEx")

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
                        versionExtract = versionExtract,
                        // Obtainium keeps '' for "use the global default", which is what no value of Tern's means.
                        minAgeDays = settingInt(settings, "minimumUpdateAgeDays")?.coerceIn(0, 365),
                        fallbackToOlder = settingBool(settings, "fallbackToOlderReleases") ?: true,
                        matchGroup = matchGroup(settings, versionExtract),
                        versionFrom = versionFrom(settings),
                        order = order(settings),
                        stayBehind = if (settingBool(settings, "stayOneVersionBehind") == true) 1 else 0,
                        versionFilter = settingString(settings, "filterVersionsByRegEx"),
                    ),
                    assets = AssetPolicy(
                        include = if (invertApkFilter) null else apkFilter,
                        exclude = if (invertApkFilter) apkFilter else null,
                        matchDevice = settingBool(settings, "autoApkFilterByArch") ?: true,
                        archives = settingBool(settings, "includeZips") == true || settingBool(settings, "includeTarballs") == true,
                        innerFilter = settingString(settings, "zippedApkFilterRegEx") ?: settingString(settings, "tarballedApkFilterRegEx"),
                    ),
                    // Exempt from background updates, an app is still checked and its updates still
                    // announced; it only does not install by itself. That is "tell me", and so is an
                    // app that is not exempt, because a file never switches on installs by themselves.
                    updates = UpdateMode.NOTIFY,
                    trackOnly = settingBool(settings, "trackOnly") == true || source.type in SourceTypes.TRACK_ONLY,
                    pinnedSigners = pinnedSigners,
                    categories = categories.map { it.take(MAX_NAME) }.take(32),
                    favorite = pinned,
                    notes = settingString(settings, "about")?.take(4000),
                    customName = settingString(settings, "appName")?.takeIf { it.isNotBlank() }?.take(MAX_NAME),
                    customAuthor = settingString(settings, "appAuthor")?.takeIf { it.isNotBlank() }?.take(MAX_NAME),
                    muted = settingBool(settings, "skipUpdateNotifications") ?: false,
                    refreshFirst = settingBool(settings, "refreshBeforeDownload") ?: false,
                    playInstaller = settingBool(settings, "shizukuPretendToBeGooglePlay") ?: false,
                ),
            )
        }
        val settings = (root as? JsonObject)?.obj("settings")?.let(ObtainiumSettings::toTern)?.takeIf { it.fields.isNotEmpty() }
        return ImportResult(apps.map(::shown), skipped, settings)
    }

    private fun mapSource(url: String, overrideSource: String?, settings: JsonObject): SourceSpec? {
        val address = Urls.normalize(url) ?: return null
        STORE_SOURCES[overrideSource]?.let { type -> return store(type, Urls.hashRouted(url) ?: address, settings) ?: store(type, address, settings) }
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
            null -> Urls.hashRouted(url)?.let { routed -> STORE_SOURCES.values.firstNotNullOfOrNull { store(it, routed, settings) } } ?: matchByUrl(address, settings)
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
        HtmlStep.write(steps(settings))?.let { options[SourceOptions.STEPS] = it }
        if (settingBool(settings, "skipSort") == true) options[SourceOptions.SORT] = "page"
        if (settingBool(settings, "filterByLinkText") == true) options[SourceOptions.LINK_TEXT] = "true"
        if (settingBool(settings, "versionExtractWholePage") == true) options[SourceOptions.VERSION_FROM] = "page"
        if (settingBool(settings, "reverseSort") == true) options[SourceOptions.FIRST_LINK] = "true"
        if ((settingBool(settings, "sortByLastLinkSegment") ?: settingBool(settings, "sortByFileNamesNotLinks")) == true) options[SourceOptions.LAST_SEGMENT] = "true"
        if (settingBool(settings, "matchLinksOutsideATags") == true) options[SourceOptions.ANY_TEXT] = "true"
        webOptions(settings, options)
        return SourceSpec(SourceTypes.HTML, address, options)
    }

    /** Obtainium's intermediate links, or the single one older versions kept, as Tern's steps with every option of each. */
    private fun steps(settings: JsonObject): List<HtmlStep> {
        val legacy = settingString(settings, "intermediateLinkRegex")?.let { filter ->
            listOf(Json.obj("customLinkFilterRegex" to filter, "filterByLinkText" to (settingBool(settings, "intermediateLinkByText") ?: false)))
        }
        val links = settings.array("intermediateLink")?.objects()?.takeIf { it.isNotEmpty() } ?: legacy.orEmpty()
        return links.mapNotNull { link ->
            HtmlStep(
                filter = settingString(link, "customLinkFilterRegex") ?: return@mapNotNull null,
                byText = settingBool(link, "filterByLinkText") == true,
                arch = settingBool(link, "autoLinkFilterByArch") == true,
                pageOrder = settingBool(link, "skipSort") == true,
                firstLink = settingBool(link, "reverseSort") == true,
                lastSegment = settingBool(link, "sortByLastLinkSegment") == true,
                anyText = settingBool(link, "matchLinksOutsideATags") == true,
            )
        }.take(HtmlStep.MAX)
    }

    /** The options Obtainium keeps for a forge or a direct download address that Tern reads too. */
    private fun withOptions(spec: SourceSpec, settings: JsonObject): SourceSpec {
        val options = LinkedHashMap(spec.options)
        when (spec.type) {
            SourceTypes.GITHUB, SourceTypes.FORGEJO -> {
                if (settingBool(settings, "verifyLatestTag") == true) options[SourceOptions.VERIFY_LATEST] = "true"
                if (settingBool(settings, "useLatestAssetDateAsReleaseDate") == true) options[SourceOptions.ASSET_DATE] = "true"
            }
            SourceTypes.DIRECT -> webOptions(settings, options)
        }
        return if (options == spec.options) spec else spec.copy(options = options)
    }

    /** The request headers and the way to tell files apart that Obtainium's HTML and direct link sources share. */
    private fun webOptions(settings: JsonObject, options: MutableMap<String, String>) {
        val headers = LinkedHashMap<String, String>()
        for (entry in settings.array("requestHeader")?.objects().orEmpty()) {
            val line = settingString(entry, "requestHeader") ?: continue
            val name = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            if (RequestHeaders.problem(name, value) != null) continue
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
            headers[name] = value
        }
        if (headers.isNotEmpty()) options[SourceOptions.HEADERS] = RequestHeaders.write(headers.entries.take(RequestHeaders.MAX_HEADERS).associate { it.key to it.value })
        val pseudo = when (settingString(settings, "defaultPseudoVersioningMethod")) {
            "partialAPKHash" -> PseudoVersion.HASH
            "APKLinkHash" -> PseudoVersion.LINK
            "ETag" -> PseudoVersion.ETAG
            else -> when (settingBool(settings, "supportFixedAPKURL")) {
                true -> PseudoVersion.HASH
                false -> PseudoVersion.LINK
                null -> null
            }
        }
        if (pseudo != null) options[SourceOptions.PSEUDO] = pseudo.option
    }

    /**
     * Obtainium takes the whole match when no group is named; Tern would take the first group, so
     * that is named. A group that is what Tern takes anyway is left unnamed, so that an app comes
     * back from a trip through Obtainium's format as it went.
     */
    private fun matchGroup(settings: JsonObject, versionExtract: String?): String? {
        if (versionExtract == null) return null
        val named = settingString(settings, "matchGroupToUse")?.takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: "0"
        return named.takeUnless { it.removePrefix("$") == ObtainiumOptions.defaultGroup(versionExtract) }
    }

    private fun versionFrom(settings: JsonObject): VersionFrom = when {
        settingBool(settings, "releaseDateAsVersion") == true || settingString(settings, "versionDetection") == "releaseDateAsVersion" -> VersionFrom.DATE
        settingBool(settings, "releaseTitleAsVersion") == true -> VersionFrom.TITLE
        else -> VersionFrom.TAG
    }

    /** Obtainium's "smart" name sorts are what Tern's version order already does. */
    private fun order(settings: JsonObject): ReleaseOrder = when (settingString(settings, "sortMethodChoice")) {
        "date" -> ReleaseOrder.DATE
        "none" -> ReleaseOrder.SOURCE
        "name" -> ReleaseOrder.NAME
        else -> if (settingBool(settings, "dontSortReleasesList") == true) ReleaseOrder.SOURCE else ReleaseOrder.VERSION
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

    /** A store source by its type. The Galaxy Store's device and region settings are not read: Tern asks every store as itself. */
    /** A store source by its type, with the Galaxy Store model and region Obtainium keeps for it. */
    private fun store(type: String, address: String, settings: JsonObject): SourceSpec? {
        val spec = REGISTRY.get(type)?.match(address) ?: return null
        if (type != SourceTypes.SAMSUNG) return spec
        val options = LinkedHashMap(spec.options)
        settingString(settings, "deviceId")?.let { options[SourceOptions.DEVICE_MODEL] = it.take(40) }
        settingString(settings, "csc")?.let { options[SourceOptions.CSC] = it.take(10) }
        return if (options == spec.options) spec else spec.copy(options = options)
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

    /** Obtainium's names for the stores and single-app sites, as its exports write them. */
    private val STORE_SOURCES = linkedMapOf(
        "HuaweiAppGallery" to SourceTypes.HUAWEI,
        "SamsungGalaxyStore" to SourceTypes.SAMSUNG,
        "VivoAppStore" to SourceTypes.VIVO,
        "Tencent" to SourceTypes.TENCENT,
        "ItchIO" to SourceTypes.ITCHIO,
        "TelegramApp" to SourceTypes.TELEGRAM,
        "NeutronCode" to SourceTypes.NEUTRONCODE,
        "APKPure" to SourceTypes.APKPURE,
        "Aptoide" to SourceTypes.APTOIDE,
        "APKCombo" to SourceTypes.APKCOMBO,
        "APKMirror" to SourceTypes.APKMIRROR,
    )

    private val REGISTRY = SourceRegistry.standard()
}
