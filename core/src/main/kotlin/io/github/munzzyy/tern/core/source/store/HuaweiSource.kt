package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.text.Shown
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Random

/**
 * Huawei AppGallery. Reads the API the AppGallery app uses (a form POST to /hwmarket/api/clientApi):
 * a client.front2 handshake gives a sign and the service zone, which picks the store's host, and
 * client.appDetailById describes the app. The handshake is kept in this object's memory for a day,
 * so most checks make one request, and it is made again when the store stops answering to it.
 */
class HuaweiSource(private val random: Random = SecureRandom()) : Source, Searchable {
    override val type: String = SourceTypes.HUAWEI

    override val origin: String = "Huawei AppGallery"

    private class Session(val host: String, val sign: String, val deviceId: String, val madeAtMs: Long)

    @Volatile
    private var session: Session? = null

    override fun match(url: String): SourceSpec? {
        // The old site routed by hash, as in appgallery.huawei.com/#/app/C100000000.
        val uri = Urls.parseHttps(url.replaceFirst("/#/", "/")) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        val segments = Urls.segments(uri.toString())
        if (segments.size < 2 || segments[0] != "app" && segments[0] != "appdl") return null
        val appId = segments[1].uppercase().takeIf { APP_ID.matches(it) } ?: return null
        val site = if (host == "appgallery.huawei.ru") host else "appgallery.huawei.com"
        return SourceSpec(type, "https://$site/app/$appId")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val appId = Urls.segments(spec.url).getOrNull(1)?.takeIf { APP_ID.matches(it) }
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Not an AppGallery address: ${spec.url}")
        val info = ask(context, { mapOf("method" to "client.appDetailById", "sign" to it.sign, "id" to appId) }) { answer ->
            answer.array("detailInfo")?.objects()?.firstOrNull()
        } ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Huawei AppGallery has no app $appId")
        val version = info.string("versionName")?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "Huawei AppGallery named no version of $appId")
        val versionCode = info.long("versionCode")?.takeIf { it > 0 }
        val pkg = info.string("package")?.takeIf { BinaryManifest.isValidName(it) }
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            publishedAtMs = info.string("releaseDate")?.let(::dayMs),
            pageUrl = spec.url,
            assets = listOfNotNull(asset(info, pkg ?: appId)),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = info.string("name"),
            author = info.string("developer")?.takeIf { it.isNotBlank() },
            packageName = pkg,
            description = info.string("briefDescription")?.takeIf { it.isNotBlank() },
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(info.string("icoUri"))))
    }

    private fun asset(info: JsonObject, stem: String): Asset? {
        val url = info.string("url")?.takeIf { Urls.isHttps(it) }?.let(Urls::normalize) ?: return null
        val host = Urls.host(url)
        if (host !in HOSTS && FILE_HOSTS.none { host == it || host.endsWith(".$it") }) return null
        val size = info.long("fullSize")?.takeIf { it > 0 } ?: info.long("size")?.takeIf { it > 0 }
        // The store names the digest of the file; the one of the signing certificate it names too is something else.
        val sha256 = info.string("sha256")?.lowercase()?.takeIf { SHA256.matches(it) }
        return Asset(name = "$stem.apk", url = url, size = size, sha256 = sha256)
    }

    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) { scoped ->
        val params = { s: Session ->
            mapOf(
                "method" to "client.getTabDetail", "sign" to s.sign, "uri" to "searchApp|$query",
                "maxResults" to MAX_HITS.toString(), "reqPageNum" to "1", "isSupportPage" to "1",
            )
        }
        ask(scoped, params) { answer ->
            answer.array("layoutData")?.objects().orEmpty().asSequence()
                .flatMap { it.array("dataList")?.objects().orEmpty() }
                .mapNotNull { hit(it.obj("appInfo") ?: it) }
                .distinctBy { it.url }
                .take(MAX_HITS)
                .toList()
                .ifEmpty { null }
        }.orEmpty()
    }

    private fun hit(app: JsonObject): Hit? {
        val appId = (app.string("appid") ?: app.string("appId"))?.uppercase()?.takeIf { APP_ID.matches(it) } ?: return null
        val name = app.string("name")?.takeIf { it.isNotBlank() } ?: return null
        return Hit(name = name, owner = null, description = app.string("memo"), url = "https://appgallery.huawei.com/app/$appId")
    }

    /**
     * One call to the store API; [read] takes what is wanted from a good answer. An answer with an
     * error is asked once more after a new handshake, and so is an empty answer to a session kept
     * from an earlier check, because the store answers a sign it no longer takes as if there were
     * nothing to find.
     */
    private fun <T : Any> ask(context: CheckContext, params: (Session) -> Map<String, String>, read: (JsonObject) -> T?): T? {
        var refusal: String? = null
        for (attempt in 0..1) {
            val kept = if (attempt == 0) session?.takeIf { context.nowMs() - it.madeAtMs in 0..SESSION_MS } else null
            val current = kept ?: handshake(context, confirm = attempt > 0)
            val answer = post(context, current.host, common(current.deviceId, context) + params(current))
            val code = answer.code()
            if (code == "0") {
                read(answer)?.let { return it }
                refusal = null
                if (kept == null) return null
            } else {
                refusal = "code $code: ${answer.string("rtnDesc")?.let { Shown.line(it, 100) }.orEmpty()}"
            }
        }
        if (refusal != null) throw SourceException(SourceErrorKind.NETWORK, "Huawei AppGallery refused the request ($refusal)")
        return null
    }

    /** Asks the European host which zone this device is in and for a sign; [confirm] asks the zone's own host for the sign as well. */
    private fun handshake(context: CheckContext, confirm: Boolean): Session {
        val deviceId = String(CharArray(64) { HEX[random.nextInt(16)] })
        val probe = post(context, HOST_EU, front2(deviceId, context, needServiceZone = true))
        val host = hostForZone(probe.string("serviceZone"))
        var sign = probe.string("sign")?.takeIf { it.isNotEmpty() }
        if (confirm || sign == null) sign = post(context, host, front2(deviceId, context, needServiceZone = false)).string("sign")?.takeIf { it.isNotEmpty() }
        if (sign == null) throw SourceException(SourceErrorKind.NETWORK, "Huawei AppGallery gave no sign at $host")
        return Session(host, sign, deviceId, context.nowMs()).also { session = it }
    }

    private fun front2(deviceId: String, context: CheckContext, needServiceZone: Boolean): Map<String, String> = common(deviceId, context) + mapOf(
        "method" to "client.front2",
        "version" to "16.5.1",
        "versionCode" to "160501301",
        "packageName" to "com.huawei.appmarket",
        "zone" to "1",
        "phoneType" to "Pixel 8 Pro",
        "firmwareVersion" to "16",
        "isFirstLaunch" to "1",
        "oobe" to "0",
        "needServiceZone" to if (needServiceZone) "1" else "0",
    )

    private fun common(deviceId: String, context: CheckContext): Map<String, String> = mapOf(
        "ver" to "1.1",
        "locale" to "en_US",
        "serviceType" to "0",
        "ts" to context.nowMs().toString(),
        "net" to "1",
        "brand" to "google",
        "manufacturer" to "Google",
        "subBrand" to "0",
        "deviceId" to deviceId,
        "deviceIdType" to "9",
    )

    /** A form with its fields in the order of their names, as the AppGallery app sends it. */
    private fun post(context: CheckContext, host: String, params: Map<String, String>): JsonObject {
        val form = params.toSortedMap().entries.joinToString("&") { (key, value) -> "${formPart(key)}=${formPart(value)}" }
        val request = HttpRequest.post("https://$host$API_PATH", form, "application/x-www-form-urlencoded", mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"))
        context.http.execute(request).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Huawei AppGallery answered ${response.status} at $host")
            return Json.parseObject(response.text(MAX_BODY))
        }
    }

    private fun formPart(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun JsonObject.code(): String? = when (val value = this["rtnCode"]) {
        is JsonNumber -> value.raw
        is JsonString -> value.value
        else -> null
    }

    private fun hostForZone(zone: String?): String = when {
        zone == "CN" -> HOST_CN
        zone == "RU" -> HOST_RU
        zone != null && zone in ZONES_ASIA -> HOST_ASIA
        else -> HOST_EU
    }

    /** The store names the day of the release, which is read as a day in UTC. */
    private fun dayMs(text: String): Long? = try {
        LocalDate.parse(text.trim()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val API_PATH = "/hwmarket/api/clientApi"
        const val USER_AGENT = "HiSpace##16.5.1.301##google##Pixel 8 Pro"
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_HITS = 25
        const val SESSION_MS = 24 * 60 * 60 * 1000L
        const val HEX = "0123456789abcdef"

        const val HOST_CN = "store-drcn.hispace.dbankcloud.com"
        const val HOST_ASIA = "store-dra.hispace.dbankcloud.com"
        const val HOST_EU = "store-dre.hispace.dbankcloud.com"
        const val HOST_RU = "store-drru.hispace.dbankcloud.ru"

        val HOSTS = setOf("appgallery.huawei.com", "appgallery.cloud.huawei.com", "appgallery.huawei.ru")

        /** Where the store keeps its files, such as appdlc-dre.hispace.dbankcloud.com. */
        val FILE_HOSTS = listOf("dbankcloud.com", "dbankcloud.ru")

        val APP_ID = Regex("^C[0-9]{1,20}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")

        /** The zones the store serves from its host for Asia, Africa and Latin America. CN and RU have hosts of their own, and every other zone is served from Europe. */
        val ZONES_ASIA: Set<String> = (
            "AE AF AG AI AM AO AQ AR AS AW AZ BB BD BF BH BI BJ BL BM BN BO BR BS BT BV BW BY BZ CC CD CF CG CI CK CL CM CO CR " +
                "CU CV CX DJ DM DO DZ EC EG EH ER ET FJ FK FM GA GD GE GF GH GM GN GP GQ GS GT GU GW GY HK HM HN HT ID IN IO IQ JM " +
                "JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LK LR LS LY MA MG MH ML MM MN MO MP MQ MR MS MU MV MW MX MY MZ NA " +
                "NC NE NF NG NI NP NR NU OM PA PE PF PG PH PK PN PR PS PW PY QA RE RW SA SB SC SD SG SH SL SN SO SR SS ST SV SY SZ " +
                "TC TD TF TG TH TJ TK TL TM TN TO TT TV TW TZ UG UY UZ VE VG VI VN VU WF WS YE YT ZA ZM ZW"
            ).split(' ').toSet()
    }
}
