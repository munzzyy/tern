package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.packageOption
import io.github.munzzyy.tern.core.source.forge.Iso8601
import io.github.munzzyy.tern.core.source.guarded
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RuStore. Reads the API RuStore's app uses (backapi.rustore.ru): the app's record, then where its
 * file is. Both want a signed session, a nonce from api.rustore.ru signed with the key in RuStore's
 * app. The session is kept in this object's memory and made again when the store answers 419.
 */
class RuStoreSource(private val random: Random = SecureRandom()) : Source, Searchable {
    override val type: String = SourceTypes.RUSTORE

    override val origin: String = "RuStore"

    private class Session(val deviceId: String, val signature: String)

    @Volatile
    private var session: Session? = null

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "rustore.ru") return null
        val segments = Urls.segments(uri.toString())
        if (segments.size < 3 || segments[0] != "catalog" || segments[1] != "app") return null
        val pkg = segments[2]
        if (!BinaryManifest.isValidName(pkg)) return null
        return SourceSpec(type, pageUrl(pkg), mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
        val info = signed(context) { HttpRequest("$API/applicationData/overallInfo/$pkg", headers = headers(context, it)) }.use { response ->
            if (response.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "RuStore has no app $pkg")
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "RuStore answered ${response.status} for $pkg")
            Json.parseObject(response.text(MAX_BODY)).obj("body")
        } ?: throw SourceException(SourceErrorKind.NOT_FOUND, "RuStore has no app $pkg")
        if (info.string("packageName")?.let { it != pkg } == true) throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
        val appId = info.long("appId") ?: throw SourceException(SourceErrorKind.NOT_FOUND, "RuStore has no app $pkg")
        val version = info.string("versionName")?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "RuStore named no version of $pkg")
        val versionCode = info.long("versionCode")?.takeIf { it > 0 }
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            notes = info.string("whatsNew")?.takeIf { it.isNotBlank() },
            notesFormat = NotesFormat.PLAIN,
            publishedAtMs = info.string("appVerUpdatedAt")?.let(Iso8601::parseMs),
            pageUrl = spec.url,
            // An app RuStore only lists from another store has no file here.
            assets = listOfNotNull(file(appId, context)?.let { Asset(name = "${pkg}_$version.apk", url = it) }),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = info.string("appName"),
            author = info.string("companyName"),
            packageName = pkg,
            description = info.string("shortDescription"),
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(info.string("iconUrl")?.let(::onPublicHost))))
    }

    /**
     * Where the file is. Tern installs one file, and asked without splits RuStore names the one
     * that holds the whole app. An app that has only a base and splits is named by its base alone,
     * the first address RuStore gives.
     */
    private fun file(appId: Long, context: CheckContext): String? {
        val device = context.device
        val body = Json.write(
            Json.obj(
                "appId" to appId,
                "firstInstall" to true,
                "withoutSplits" to true,
                "supportedAbis" to (device?.abis ?: listOf("arm64-v8a")),
                "sdkVersion" to (device?.sdk ?: DEFAULT_SDK),
                "screenDensity" to (device?.densityDpi ?: DEFAULT_DENSITY),
            ),
        )
        val answer = signed(context) {
            HttpRequest.post("$API/v3/showcase/apps/download-link", body, "application/json; charset=utf-8", headers(context, it)).copy(followRedirects = false)
        }.use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "RuStore answered ${response.status} when asked for the file of app $appId")
            Json.parseObject(response.text(MAX_BODY))
        }
        val first = answer.array("downloadUrls")?.objects().orEmpty().firstNotNullOfOrNull { it.string("url")?.takeIf { url -> url.isNotBlank() } }
        return first?.let(::apkAddress)
    }

    /**
     * RuStore names each file as a .zip that holds the APK, and serves the APK itself beside it
     * under the same name, which is what is listed.
     */
    private fun apkAddress(url: String): String? = onPublicHost(url)?.replace(ZIP_END, ".apk")

    /**
     * An https address on RuStore's hosts. Its file host static-m.rustore.ru presents a certificate
     * from the Russian Trusted Root CA, which Android does not trust, while static.rustore.ru serves
     * the same files with a certificate from a public authority, so files are asked for there.
     */
    private fun onPublicHost(url: String): String? {
        if (!Urls.isHttps(url)) return null
        val normalized = Urls.normalize(url) ?: return null
        val host = Urls.host(normalized)
        if (host != HOME && !host.endsWith(".$HOME")) return null
        return if (host == "static-m.$HOME") "https://static.$HOME" + normalized.removePrefix("https://static-m.$HOME") else normalized
    }

    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) { scoped ->
        val url = "$API/applicationData/apps?query=${Urls.encodeSegment(query)}&pageNumber=0&pageSize=$MAX_HITS"
        // The search wants no signature, but a device id.
        val deviceId = session?.deviceId ?: deviceId()
        val root = scoped.http.execute(HttpRequest(url, headers = deviceHeaders(scoped, deviceId))).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "RuStore answered ${response.status} to a search")
            Json.parseObject(response.text(MAX_BODY))
        }
        root.obj("body")?.array("content")?.objects().orEmpty().asSequence().mapNotNull(::hit).take(MAX_HITS).toList()
    }

    private fun hit(app: JsonObject): Hit? {
        val pkg = app.string("packageName")?.takeIf { BinaryManifest.isValidName(it) } ?: return null
        val name = app.string("appName")?.takeIf { it.isNotBlank() } ?: return null
        return Hit(name = name, owner = null, description = app.string("shortDescription"), url = pageUrl(pkg))
    }

    /** [request] with the kept session, and once more with a new one when the store answers 419. */
    private fun signed(context: CheckContext, request: (Session) -> HttpRequest): HttpResponse {
        val response = context.http.execute(request(session ?: newSession(context)))
        if (response.status != 419) return response
        response.close()
        return context.http.execute(request(newSession(context)))
    }

    private fun newSession(context: CheckContext): Session {
        val deviceId = deviceId()
        val nonce = context.http.execute(HttpRequest(NONCE_URL, method = "POST", headers = deviceHeaders(context, deviceId), body = ByteArray(0))).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "RuStore answered ${response.status} when asked for a session")
            Json.parseObject(response.text(64 * 1024)).string("nonce")
        } ?: throw SourceException(SourceErrorKind.NETWORK, "RuStore gave no session")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(HMAC_KEY), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(Base64.getDecoder().decode(nonce) + Base64.getDecoder().decode(APP_CERT_SHA256)))
        return Session(deviceId, signature).also { session = it }
    }

    private fun headers(context: CheckContext, session: Session): Map<String, String> =
        deviceHeaders(context, session.deviceId) + ("X-Client-Signature" to session.signature)

    /** What RuStore's app says about the phone it runs on, a Pixel 8 Pro as Obtainium says. */
    private fun deviceHeaders(context: CheckContext, deviceId: String): Map<String, String> = mapOf(
        "deviceId" to deviceId,
        "firmwareVer" to "16",
        "androidSdkVer" to "36",
        "deviceManufacturerName" to MANUFACTURER,
        "deviceModelName" to MODEL,
        "deviceModel" to "$MANUFACTURER $MODEL",
        "firmwareLang" to "ru",
        "ruStoreVerCode" to "1105002",
        "deviceType" to if (context.device?.television == true) "TV" else "mobile",
        "User-Agent" to "RuStore/1.105.0.2 (Android 16; SDK 36; arm64-v8a; $MANUFACTURER $MODEL; ru)",
    )

    /** A random Android id and a number made from the phone's names, as RuStore's app makes its device id. */
    private fun deviceId(): String {
        val androidId = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val suffix = listOf(MANUFACTURER, MODEL, HARDWARE, HARDWARE).fold(0) { hash, name -> hash * 31 + name.hashCode() }
        return "$androidId-$suffix"
    }

    private companion object {
        const val HOME = "rustore.ru"
        const val API = "https://backapi.rustore.ru"
        const val NONCE_URL = "https://api.rustore.ru/v1/secure/nonce"
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_HITS = 20
        const val DEFAULT_SDK = 36
        const val DEFAULT_DENSITY = 420
        const val MANUFACTURER = "Google"
        const val MODEL = "Pixel 8 Pro"
        const val HARDWARE = "husky"

        /** The key RuStore's app signs its nonce with, and the SHA-256 of that app's certificate, which is signed along with it. */
        const val HMAC_KEY = "K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU="
        const val APP_CERT_SHA256 = "Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ="

        val ZIP_END = Regex("\\.zip$")

        fun pageUrl(pkg: String) = "https://www.rustore.ru/catalog/app/$pkg"
    }
}
