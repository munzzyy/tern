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
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * vivo App Store (China). Reads the store's detail API (h5-api.appstore.vivo.com.cn/detailInfo) for
 * the app its address names by appId, and lists the store's download address, which [resolve]
 * follows to the file on vivo's CDN.
 */
class VivoSource : Source, Searchable {
    override val type: String = SourceTypes.VIVO

    override val origin: String = "vivo App Store"

    override val domains: Set<String> get() = setOf(FILE_HOST)

    override fun match(url: String): SourceSpec? {
        // The mobile site routes by hash, as in h5.appstore.vivo.com.cn/#/details?appId=123.
        val uri = Urls.parseHttps(url.replaceFirst("/#", "")) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        val appId = Urls.queryParam(uri.toString(), "appId")?.takeIf { APP_ID.matches(it) } ?: return null
        return SourceSpec(type, pageUrl(appId))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val appId = appIdOf(spec)
        val url = "https://h5-api.appstore.vivo.com.cn/detailInfo?appId=$appId"
        val detail = context.http.execute(HttpRequest(url)).use { response ->
            if (response.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "vivo has no app $appId")
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "vivo answered ${response.status} for app $appId")
            Json.parseObject(response.text(MAX_BODY))
        }
        // An app vivo does not know is answered with a record that has no id.
        val id = detail.long("id") ?: throw SourceException(SourceErrorKind.NOT_FOUND, "vivo has no app $appId")
        if (id.toString() != appId) throw SourceException(SourceErrorKind.PARSE, "Asked for app $appId and was answered for another")
        val versionName = detail.string("version_name")?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "vivo named no version of app $appId")
        val versionCode = detail.long("version_code")?.takeIf { it > 0 }
        val pkg = detail.string("package_name")?.takeIf { BinaryManifest.isValidName(it) }
        val asset = Asset(name = "${pkg ?: "vivo-$appId"}_${versionCode ?: versionName}.apk", url = fileUrl(appId))
        val release = Release(
            id = versionCode?.toString() ?: versionName,
            version = versionName,
            versionCode = versionCode,
            publishedAtMs = detail.string("upload_time")?.let(::uploadedAtMs),
            pageUrl = spec.url,
            assets = listOf(asset),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = detail.string("title_zh"),
            author = detail.string("developer"),
            packageName = pkg,
            description = detail.string("app_remark"),
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(detail.string("icon_url"))))
    }

    /**
     * The store's download address answers with a redirect to its CDN over plain http, which Tern
     * never uses. The same address is asked for over https instead. When the CDN does not answer
     * https, the download fails; it did answer when this was written.
     */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) { scoped ->
        val address = Urls.queryParam(asset.url, "id")?.takeIf { APP_ID.matches(it) }?.let(::fileUrl)?.takeIf { it == asset.url }
            ?: throw SourceException(SourceErrorKind.PARSE, "Not a vivo download address: ${asset.url}")
        scoped.http.execute(HttpRequest(address, followRedirects = false)).use { response ->
            when {
                response.isSuccess -> Download(address)
                response.status in 300..399 -> {
                    val location = response.headers["Location"]
                        ?: throw SourceException(SourceErrorKind.PARSE, "vivo sent a redirect without an address")
                    val target = secured(address, location)
                        ?: throw SourceException(SourceErrorKind.PARSE, "vivo sent the download to a host it does not use")
                    Download(target)
                }
                response.status == 404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "vivo has no file at $address")
                else -> throw SourceException(SourceErrorKind.NETWORK, "vivo answered ${response.status} for $address")
            }
        }
    }

    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) { scoped ->
        val url = "https://h5-api.appstore.vivo.com.cn/h5appstore/search/result-list?app_version=2100&page_index=1" +
            "&apps_per_page=$MAX_HITS&target=local&cfrom=2&key=${Urls.encodeSegment(query)}"
        val root = scoped.http.execute(HttpRequest(url)).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "vivo answered ${response.status} to a search")
            Json.parseObject(response.text(MAX_BODY))
        }
        if (root.long("code") != 0L) throw SourceException(SourceErrorKind.NETWORK, "vivo refused the search")
        val found = root.obj("data")?.obj("appSearchResponse")?.takeIf { it.bool("result") == true }
        found?.array("value")?.objects().orEmpty().asSequence().mapNotNull(::hit).take(MAX_HITS).toList()
    }

    private fun hit(app: JsonObject): Hit? {
        val appId = app.long("id")?.toString() ?: return null
        val name = app.string("title_zh")?.takeIf { it.isNotBlank() } ?: return null
        return Hit(name = name, owner = app.string("developer"), description = app.string("remark"), url = pageUrl(appId))
    }

    private fun appIdOf(spec: SourceSpec): String =
        Urls.queryParam(spec.url, "appId")?.takeIf { APP_ID.matches(it) }
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Not a vivo App Store address: ${spec.url}")

    /** [location] resolved against [base], moved to https, and kept only on vivo's own hosts. */
    private fun secured(base: String, location: String): String? {
        val resolved = try {
            URI(base).resolve(location.trim())
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = resolved.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val url = Urls.normalize(resolved.toString()) ?: return null
        val host = Urls.host(url)
        return url.takeIf { host == FILE_HOST || host.endsWith(".$FILE_HOST") }
    }

    /** vivo writes the time of upload as the time in China. */
    private fun uploadedAtMs(text: String): Long? = try {
        LocalDateTime.parse(text.trim(), UPLOAD_TIME).toInstant(CHINA).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_HITS = 20
        val HOSTS = setOf("h5.appstore.vivo.com.cn", "h5coml.vivo.com.cn", "detail-browser.vivo.com.cn")

        /** The store and its CDN, such as apkwificdn2-v6dl.vivo.com.cn. */
        const val FILE_HOST = "vivo.com.cn"
        val APP_ID = Regex("^[0-9]{1,12}$")
        val UPLOAD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val CHINA: ZoneOffset = ZoneOffset.ofHours(8)

        fun pageUrl(appId: String) = "https://detail-browser.vivo.com.cn/v115/index.html?appId=$appId"

        fun fileUrl(appId: String) = "https://appstore.vivo.com.cn/appinfo/downloadApkFile?id=$appId"
    }
}
