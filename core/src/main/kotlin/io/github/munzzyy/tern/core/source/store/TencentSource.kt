package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.packageOption
import io.github.munzzyy.tern.core.source.guarded

/**
 * Tencent App Store (sj.qq.com). Reads the app's download page on a.app.qq.com, which carries the
 * store's record of the app as JSON in window.systemData, and lists the file that record names.
 */
class TencentSource : Source {
    override val type: String = SourceTypes.TENCENT

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val normalized = uri.toString()
        val pkg = when (uri.host?.lowercase()) {
            "sj.qq.com" -> Urls.segments(normalized).takeIf { it.size >= 2 && it[0] == "appdetail" }?.get(1)
            "a.app.qq.com" -> Urls.queryParam(normalized, "pkgname").takeIf { uri.path == "/o/simple.jsp" }
            else -> null
        } ?: return null
        if (!BinaryManifest.isValidName(pkg)) return null
        return SourceSpec(type, "https://sj.qq.com/appdetail/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
        val url = "https://a.app.qq.com/o/simple.jsp?pkgname=$pkg"
        // A redirect means the store has no page for the app, as Obtainium reads it too.
        val page = context.http.execute(HttpRequest(url, followRedirects = false)).use { response ->
            if (response.status == 404 || response.status in 300..399) throw SourceException(SourceErrorKind.NOT_FOUND, "Tencent App Store has no app $pkg")
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Tencent App Store answered ${response.status} for $pkg")
            response.text(MAX_BODY)
        }
        // For an app it does not know the page describes its own app store, in a form that is not JSON.
        val data = page.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(DATA) }
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Tencent App Store has no app $pkg")
        val detail = Json.parseObject(data.removePrefix(DATA).trimEnd(';')).obj("appDetail")
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Tencent App Store has no app $pkg")
        if (detail.string("packageName") != pkg) throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
        val version = detail.string("versionName")?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "Tencent App Store named no version of $pkg")
        val versionCode = detail.long("versionCode")?.takeIf { it > 0 }
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            pageUrl = spec.url,
            assets = listOfNotNull(asset(detail, pkg, version, context)),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = detail.string("appName"),
            author = detail.string("author") ?: detail.string("developer"),
            packageName = pkg,
            description = detail.string("editorIntro"),
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(detail.string("iconUrl")?.let(::secured))))
    }

    /** The store names a build for 64-bit phones and one for the rest; the first is taken unless the phone runs only 32-bit code. */
    private fun asset(detail: JsonObject, pkg: String, version: String, context: CheckContext): Asset? {
        val only32 = context.device?.abis?.none { it in ABIS_64 } == true
        val names = if (only32) listOf("apkUrl", "apkUrl64") else listOf("apkUrl64", "apkUrl")
        val url = names.firstNotNullOfOrNull { name -> detail.string(name)?.takeIf { it.isNotBlank() } }?.let(::secured) ?: return null
        if (FILE_HOSTS.none { Urls.host(url) == it || Urls.host(url).endsWith(".$it") }) return null
        val named = Urls.queryParam(url, "fsname")?.takeIf { it.endsWith(".apk", ignoreCase = true) && '/' !in it }
        val size = detail.obj("fileSize")?.long("bytes")?.takeIf { it > 0 }
        return Asset(name = named ?: "${pkg}_$version.apk", url = url, size = size)
    }

    /**
     * The store names its files and icons over plain http, which Tern never uses. Its CDN answers the
     * same addresses over https, so those are asked for instead.
     */
    private fun secured(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !Urls.isHttps(trimmed)) return null
        return Urls.normalize(trimmed)
    }

    private companion object {
        const val MAX_BODY = 4 * 1024 * 1024
        const val DATA = "window.systemData="

        /** Where the store keeps its files, such as imtt.dd.qq.com, and its older CDN, dd.myapp.com. */
        val FILE_HOSTS = listOf("dd.qq.com", "myapp.com")
        val ABIS_64 = setOf("arm64-v8a", "x86_64")
    }
}
