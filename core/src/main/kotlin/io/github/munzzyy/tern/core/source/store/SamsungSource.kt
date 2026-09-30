package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.model.Asset
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
import io.github.munzzyy.tern.core.source.fdroid.packageOption
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Galaxy Store. Reads the download stub the store's own app asks (vas.samsungapps.com), which names
 * the newest version for one device model and region and a file address that holds a token for a
 * short while. The file is listed at that address without the token, so it stays the same from one
 * check to the next, and [resolve] asks the stub again for a fresh one.
 */
class SamsungSource : Source {
    override val type: String = SourceTypes.SAMSUNG

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        val normalized = uri.toString()
        val pkg = Urls.queryParam(normalized, "appId")
            ?: Urls.segments(normalized).lastOrNull()?.takeUnless { segment -> PAGES.any { segment.endsWith(it) } }
            ?: return null
        if (!BinaryManifest.isValidName(pkg)) return null
        return SourceSpec(type, "https://apps.galaxyappstore.com/detail/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
        val stub = stub(spec, pkg, context)
        val file = stub.fileUrl?.takeIf(::isStoreFile)?.let(Urls::normalize)?.substringBefore('?')
        val release = Release(
            id = stub.versionCode?.toString() ?: stub.versionName,
            version = stub.versionName,
            versionCode = stub.versionCode,
            publishedAtMs = file?.let(::uploadedAtMs),
            pageUrl = spec.url,
            assets = listOfNotNull(file?.let { Asset(name = "$pkg.apk", url = it, size = stub.size) }),
        )
        return CheckResult.Listing(SourceListing(releases = listOf(release), name = stub.name, packageName = pkg))
    }

    /** The address in the listing lacks the token the file host asks for, so a fresh one is asked for each download. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) {
        val fresh = stub(spec, packageOption(spec), it).fileUrl
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The Galaxy Store named no file for ${spec.url}")
        if (!isStoreFile(fresh)) throw SourceException(SourceErrorKind.PARSE, "The Galaxy Store named a file on a host it does not use")
        Download(fresh)
    }

    private class Stub(val versionName: String, val versionCode: Long?, val name: String?, val fileUrl: String?, val size: Long?)

    private fun stub(spec: SourceSpec, pkg: String, context: CheckContext): Stub {
        val model = spec.option(SourceOptions.DEVICE_MODEL) ?: DEFAULT_MODEL
        val csc = spec.option(SourceOptions.CSC) ?: DEFAULT_CSC
        val device = context.device
        val sdk = device?.sdk ?: DEFAULT_SDK
        // The store serves a separate build to phones that run only 32-bit code.
        val abiType = if (device != null && device.abis.none { it in ABIS_64 }) "32" else "64"
        val url = "https://vas.samsungapps.com/stub/stubDownload.as?appId=$pkg" +
            "&deviceId=${Urls.encodeSegment(model)}&mcc=425&mnc=01&csc=${Urls.encodeSegment(csc)}&sdkVer=$sdk" +
            "&systemId=1608665720954&abiType=$abiType&extuk=0191d6627f38685f"
        context.http.execute(HttpRequest(url)).use { response ->
            if (response.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "The Galaxy Store has no app $pkg")
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "The Galaxy Store answered ${response.status} for $pkg")
            val root = XmlScanner.parse(response.text(MAX_BODY))
            if (root.childText("resultCode") != "1") {
                val reason = root.childText("resultMsg")?.let { Shown.lineOrNull(it, 200) } ?: "no reason given"
                throw SourceException(SourceErrorKind.NOT_FOUND, "The Galaxy Store has no file of $pkg for this device: $reason")
            }
            if (root.childText("appId")?.let { it != pkg } == true) {
                throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
            }
            val versionName = root.childText("versionName")
                ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The Galaxy Store named no version of $pkg")
            return Stub(
                versionName = versionName,
                versionCode = root.childText("versionCode")?.toLongOrNull()?.takeIf { it > 0 },
                name = root.childText("productName"),
                fileUrl = root.childText("downloadURI"),
                size = root.childText("contentSize")?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
    }

    private fun isStoreFile(url: String): Boolean {
        if (!Urls.isHttps(url)) return false
        val host = Urls.normalize(url)?.let(Urls::host) ?: return false
        return FILE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** The file name carries the time of the upload, such as prePost_20260702073935007.apk. The store does not say in which zone; it is read as UTC. */
    private fun uploadedAtMs(url: String): Long? {
        val stamp = STAMP.find(url.substringAfterLast('/'))?.groupValues?.get(1) ?: return null
        return try {
            val time = LocalDateTime.of(
                stamp.substring(0, 4).toInt(), stamp.substring(4, 6).toInt(), stamp.substring(6, 8).toInt(),
                stamp.substring(8, 10).toInt(), stamp.substring(10, 12).toInt(), stamp.substring(12, 14).toInt(),
            )
            val millis = if (stamp.length >= 17) stamp.substring(14, 17).toLong() else 0L
            time.toInstant(ZoneOffset.UTC).toEpochMilli() + millis
        } catch (_: DateTimeException) {
            null
        }
    }

    companion object {
        /** Asked for when the app names no model: the model and region Obtainium asks for too. */
        const val DEFAULT_MODEL = "SM-S948B"
        const val DEFAULT_CSC = "DBT"
        private const val DEFAULT_SDK = 36
        private const val MAX_BODY = 256 * 1024

        private val HOSTS = setOf("galaxystore.samsung.com", "apps.samsung.com", "apps.samsung.cn", "galaxyappstore.com", "apps.galaxyappstore.com")

        /** The store's file hosts, such as cflare-dn.gw.samsungapps.com, and the Chinese store's. */
        private val FILE_HOSTS = listOf("samsungapps.com", "galaxyappstore.com")

        private val ABIS_64 = setOf("arm64-v8a", "x86_64")

        /** Last segments that name a page of the store rather than an app. */
        private val PAGES = listOf(".as", ".jsp", ".html", ".htm")
        private val STAMP = Regex("(\\d{14,17})")
    }
}
