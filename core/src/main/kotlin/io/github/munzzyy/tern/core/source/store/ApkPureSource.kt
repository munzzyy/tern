package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.forge.Iso8601
import io.github.munzzyy.tern.core.source.guarded

/**
 * APKPure, read from the version history its own app asks tapi.pureapk.com for. Each version is one
 * release with a file for every variant the store holds of it, named after the package, the version
 * code and the processors the variant is built for. On a known device, variants built only for
 * processors it lacks are left out.
 *
 * A file address carries a token that runs out, so a file is listed by its address without the
 * query, and [resolve] asks the history again for one that works now.
 */
class ApkPureSource : Source {
    override val type: String = SourceTypes.APKPURE

    override val republishes: Boolean get() = true

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val site = SITES.firstOrNull { host == it || host == "www.$it" || host == "m.$it" } ?: return null
        val segments = Urls.segments(uri.toString())
        // An address may start with a two-letter language, as in /de/<name>/<package>.
        val readings = if (segments.size >= 3 && segments[0].length == 2) listOf(segments.drop(1), segments) else listOf(segments)
        val (slug, pkg) = readings.firstOrNull { it.size >= 2 && it[0].any(Char::isLetterOrDigit) && FDroidSource.isValidPackage(it[1]) } ?: return null
        return SourceSpec(type, "https://$site/${Urls.encodeSegment(slug)}/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOf(spec)
        val entries = history(pkg, context)
        val versions = LinkedHashMap<String, MutableList<JsonObject>>()
        for (entry in entries) {
            val version = entry.string("version_name")?.trim().orEmpty()
            if (version.isEmpty() || entry.string("package_name")?.let { it != pkg } == true) continue
            versions.getOrPut(version) { ArrayList() }.add(entry)
        }
        val releases = versions.entries.asSequence()
            .mapNotNull { (version, variants) -> release(spec, pkg, version, variants, context.device) }
            .take(MAX_RELEASES)
            .toList()
        if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No version of $pkg on APKPure has a file for this device")
        val first = entries.first()
        val icon = first.obj("icon")
        val listing = SourceListing(
            releases = releases,
            name = first.string("title") ?: first.string("label"),
            author = first.string("developer"),
            packageName = pkg,
            description = first.string("description_short"),
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(icon?.obj("original")?.string("url"), icon?.obj("thumbnail")?.string("url"))))
    }

    private fun release(spec: SourceSpec, pkg: String, version: String, variants: List<JsonObject>, device: DeviceProfile?): Release? {
        val files = LinkedHashMap<String, Asset>()
        val codes = ArrayList<Long>()
        var published: Long? = null
        for (variant in variants) {
            val code = variant.long("version_code") ?: continue
            val abis = variant.array("native_code")?.strings().orEmpty().filter { ABI.matches(it) }
            if (!fits(abis, device)) continue
            val file = variant.obj("asset") ?: continue
            val extension = file.string("type")?.lowercase()?.takeIf { EXTENSION.matches(it) } ?: continue
            val address = file.string("url")?.let(::stableAddress) ?: continue
            val name = "$pkg-$code" + (if (abis.isEmpty()) "" else "-" + abis.joinToString(",")) + ".$extension"
            val kind = Asset.kindOf(name)
            if ((kind != AssetKind.APK && kind != AssetKind.BUNDLE) || name in files) continue
            val sha256 = file.string("file_sha256")?.lowercase()?.takeIf { SHA256.matches(it) }
            files[name] = Asset(name = name, url = address, size = file.long("size")?.takeIf { it > 0 }, sha256 = sha256, kind = kind)
            codes.add(code)
            if (published == null) published = variant.string("update_date")?.let(Iso8601::parseMs)
        }
        if (files.isEmpty()) return null
        val notes = variants.firstNotNullOfOrNull { it.string("whatsnew")?.takeIf(String::isNotBlank) }
        return Release(
            id = version,
            version = version,
            // Variants of one version may carry different codes; the lowest keeps any of them from reading as out of date.
            versionCode = codes.min(),
            notes = notes,
            notesFormat = NotesFormat.HTML,
            publishedAtMs = published,
            prerelease = variants.first().bool("is_beta") == true,
            pageUrl = spec.url,
            assets = files.values.toList(),
        )
    }

    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) {
        val pkg = packageOf(spec)
        val fresh = history(pkg, it).asSequence()
            .mapNotNull { entry -> entry.obj("asset")?.string("url") }
            .firstOrNull { address -> stableAddress(address) == asset.url }
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "APKPure no longer offers ${asset.name}")
        Download(Urls.normalize(fresh) ?: throw SourceException(SourceErrorKind.PARSE, "APKPure named a file at an address that cannot be read"))
    }

    private fun packageOf(spec: SourceSpec): String {
        val pkg = spec.option(SourceOptions.PACKAGE) ?: Urls.segments(spec.url).lastOrNull()
        if (pkg == null || !FDroidSource.isValidPackage(pkg)) throw SourceException(SourceErrorKind.UNSUPPORTED, "${spec.url} names no app on APKPure")
        return pkg
    }

    /** The versions of [pkg], newest first, each variant of a version as an entry of its own. */
    private fun history(pkg: String, context: CheckContext): List<JsonObject> {
        val sdk = context.device?.sdk ?: DEFAULT_SDK
        val headers = mapOf(
            "Ual-Access-Businessid" to "projecta",
            "Ual-Access-ProjectA" to """{"device_info":{"os_ver":"$sdk"}}""",
        )
        val url = "$HISTORY?package_name=$pkg&hl=en"
        context.http.execute(HttpRequest(url, headers = headers)).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "APKPure has no app $pkg")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} from APKPure")
            val answer = Json.parseObject(it.text(BODY_CAP))
            val code = answer.long("retcode")
            if (code != null && code != 0L) throw SourceException(SourceErrorKind.NETWORK, "APKPure refused the request: ${answer.string("errmsg").orEmpty()}")
            val entries = answer.array("version_list") ?: throw SourceException(SourceErrorKind.PARSE, "APKPure sent no version list")
            return entries.objects().ifEmpty { throw SourceException(SourceErrorKind.NOT_FOUND, "APKPure has no app $pkg") }
        }
    }

    private fun fits(abis: List<String>, device: DeviceProfile?): Boolean =
        device == null || abis.isEmpty() || abis.any { it in ANY_ABI || it in device.abis }

    /** The file's address without the query, whose token runs out; null for anything but an https address on the file host. */
    private fun stableAddress(url: String): String? {
        if (!Urls.isHttps(url)) return null
        val normalized = Urls.normalize(url) ?: return null
        if (Urls.host(normalized) !in FILE_HOSTS) return null
        return normalized.substringBefore('?').takeIf { Urls.segments(it).isNotEmpty() }
    }

    companion object {
        private const val HISTORY = "https://tapi.pureapk.com/v3/get_app_his_version"
        private const val MAX_RELEASES = 30
        private const val BODY_CAP = 4 * 1024 * 1024

        /** Sent as the Android version when the device is not known. */
        private const val DEFAULT_SDK = 35

        private val SITES = listOf("apkpure.com", "apkpure.net")

        /** Where the history says APKPure's files are. */
        val FILE_HOSTS = setOf("data.winudf.com")

        /** What the history says of a variant that runs on every processor. */
        private val ANY_ABI = setOf("universal", "unlimited")
        private val ABI = Regex("[A-Za-z0-9_-]{1,20}")
        private val EXTENSION = Regex("[a-z]{3,4}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
