package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.Asset
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
import io.github.munzzyy.tern.core.source.fdroid.packageOption
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.text.Shown
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

/**
 * CoolApk. Reads the app's record from the API CoolApk's own app uses (api2.coolapk.com), which
 * answers only requests that carry that app's headers and a token made from the time and a device
 * code. The file is listed at the API's download address, and [resolve] asks it where the file is.
 */
class CoolApkSource(private val random: Random = SecureRandom()) : Source {
    override val type: String = SourceTypes.COOLAPK

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "coolapk.com") return null
        val segments = Urls.segments(uri.toString())
        if (segments.size < 2 || segments[0] != "apk") return null
        val pkg = segments[1]
        if (!BinaryManifest.isValidName(pkg)) return null
        return SourceSpec(type, "https://www.coolapk.com/apk/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
        val root = context.http.execute(HttpRequest("$API/v6/apk/detail?id=$pkg", headers = headers(context))).use { response ->
            if (response.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "CoolApk has no app $pkg")
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "CoolApk answered ${response.status} for $pkg")
            Json.parseObject(response.text(MAX_BODY))
        }
        val detail = root.obj("data") ?: run {
            if (root.long("status") == -2L) throw SourceException(SourceErrorKind.NOT_FOUND, "CoolApk has no app $pkg")
            val reason = root.string("message")?.let { Shown.lineOrNull(it, 200) } ?: "no reason given"
            throw SourceException(SourceErrorKind.NETWORK, "CoolApk refused the request for $pkg: $reason")
        }
        val answered = detail.string("apkname") ?: detail.string("packageName")
        if (answered != null && answered != pkg) throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
        val version = detail.string("apkversionname")?.takeIf { it.isNotBlank() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "CoolApk named no version of $pkg")
        val versionCode = detail.long("apkversioncode")?.takeIf { it > 0 }
        val appId = detail.long("id")?.takeIf { it > 0 } ?: throw SourceException(SourceErrorKind.PARSE, "CoolApk named no id for $pkg")
        val asset = Asset(name = "${pkg}_$version.apk", url = fileUrl(pkg, appId, versionCode), size = detail.long("apklength")?.takeIf { it > 0 })
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            notes = detail.string("changelog")?.takeIf { it.isNotBlank() },
            notesFormat = NotesFormat.PLAIN,
            publishedAtMs = detail.long("lastupdate")?.takeIf { it > 0 }?.let { it * 1000 },
            pageUrl = spec.url,
            assets = listOf(asset),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = detail.string("title"),
            author = detail.string("developername")?.takeIf { it.isNotBlank() },
            packageName = pkg,
            description = detail.string("subtitle")?.takeIf { it.isNotBlank() },
        )
        // Logos are named over plain http, on hosts that answer https as well.
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(detail.string("logo")?.let(Urls::normalize))))
    }

    /** The download address answers with a redirect to where the file is now, and only to requests with a fresh token. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = guarded(context) { scoped ->
        val pkg = packageOption(spec)
        val appId = Urls.queryParam(asset.url, "aid")?.toLongOrNull()
        val address = appId?.let { fileUrl(pkg, it, Urls.queryParam(asset.url, "vc")?.toLongOrNull()) }?.takeIf { it == asset.url }
            ?: throw SourceException(SourceErrorKind.PARSE, "Not a CoolApk download address: ${asset.url}")
        scoped.http.execute(HttpRequest(address, headers = headers(scoped), followRedirects = false)).use { response ->
            if (response.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "CoolApk has no file of $pkg")
            if (response.status !in 300..399) throw SourceException(SourceErrorKind.NETWORK, "CoolApk answered ${response.status} for the file of $pkg")
            val target = response.headers["Location"]?.let { Urls.resolve(address, it) }?.takeIf(::onFileHost)
                ?: throw SourceException(SourceErrorKind.PARSE, "CoolApk sent the download to a host it does not use")
            Download(target)
        }
    }

    /** The headers CoolApk's app sends, with a new device code and a token for this moment. */
    private fun headers(context: CheckContext): Map<String, String> {
        val device = CoolApkToken.deviceCode(ByteArray(16).also(random::nextBytes), ByteArray(6).also(random::nextBytes))
        return APP_HEADERS + mapOf("X-App-Device" to device, "X-App-Token" to CoolApkToken.token(context.nowMs() / 1000, device))
    }

    private fun onFileHost(url: String): Boolean {
        val host = Urls.host(url)
        return FILE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private companion object {
        const val API = "https://api2.coolapk.com"
        const val MAX_BODY = 4 * 1024 * 1024

        /** The API's download address sends to dl.coolapk.com, which sends on to a CDN such as dl-t2.coolapkmarket.com. */
        val FILE_HOSTS = listOf("coolapk.com", "coolapkmarket.com")

        /** What CoolApk's app says about itself. The version is the one Obtainium sends. */
        val APP_HEADERS = mapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9; MI 8 SE MIUI/9.5.9) (#Build; Xiaomi; MI 8 SE; PKQ1.181121.001; 9) +CoolMarket/12.4.2-2208241-universal",
            "X-App-Id" to "com.coolapk.market",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sdk-Int" to "30",
            "X-App-Mode" to "universal",
            "X-App-Channel" to "coolapk",
            "X-Sdk-Locale" to "zh-CN",
            "X-App-Version" to "12.4.2",
            "X-Api-Supported" to "2208241",
            "X-App-Code" to "2208241",
            "X-Api-Version" to "12",
            "X-Dark-Mode" to "0",
        )

        fun fileUrl(pkg: String, appId: Long, versionCode: Long?): String =
            "$API/v6/apk/download?pn=$pkg&aid=$appId" + (versionCode?.let { "&vc=$it" } ?: "")
    }
}

/** The device code and the second version of the token, made the way CoolApk's app makes them and as Obtainium makes them. */
internal object CoolApkToken {
    /** A device code from a random Android id and MAC address, for a phone that says it is a Pixel 5a. */
    fun deviceCode(androidId: ByteArray, mac: ByteArray): String {
        val id = androidId.joinToString("") { "%02X".format(it) }
        val address = mac.joinToString(":") { "%02x".format(it) }
        return base64("$id; ; ; $address; Google; Google; Pixel 5a; SQ1D.220105.007")
    }

    /** The token for the time in [seconds] and a [deviceCode]. */
    fun token(seconds: Long, deviceCode: String): String {
        val time = seconds.toString()
        val token = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c?${md5(time)}\$${md5(deviceCode)}&com.coolapk.market"
        val salt = "\$2a\$10\$${base64(time).take(14)}/${md5(token).take(6)}u"
        val hashed = Bcrypt.hash(md5(base64(token)), salt)
        return "v2" + base64("\$2y" + hashed.substring(3))
    }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** bcrypt as jBCrypt computes it, for the "$2a$" salts the token needs. */
internal object Bcrypt {
    private const val ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val SALT = Regex("""^\$2a\$(\d\d)\$([./A-Za-z0-9]{22})""")

    /** "OrpheanBeholderScryDoubt", the text bcrypt enciphers, as six big-endian words. */
    private val MAGIC = intArrayOf(0x4f727068, 0x65616e42, 0x65686f6c, 0x64657253, 0x63727944, 0x6f756274)

    /** [password] hashed with [salt], a string that begins "$2a$", two digits of cost, "$" and 22 characters of salt. */
    fun hash(password: String, salt: String): String {
        val match = SALT.find(salt) ?: throw IllegalArgumentException("Not a bcrypt salt")
        val cost = match.groupValues[1].toInt()
        require(cost in 4..30) { "bcrypt cost out of range" }
        val saltBytes = decode(match.groupValues[2], 16)
        // The "a" revision counts the zero that ends the password as part of it.
        val key = (password + "\u0000").toByteArray(Charsets.UTF_8)
        return "\$2a\$${match.groupValues[1]}\$" + encode(saltBytes, 16) + encode(crypt(key, saltBytes, cost), 23)
    }

    private fun crypt(key: ByteArray, salt: ByteArray, cost: Int): ByteArray {
        val p = Blowfish.INITIAL.copyOfRange(0, 18)
        val s = Blowfish.INITIAL.copyOfRange(18, 18 + 1024)
        expand(p, s, key, salt)
        repeat(1 shl cost) {
            expand(p, s, key, null)
            expand(p, s, salt, null)
        }
        val words = MAGIC.copyOf()
        repeat(64) {
            for (j in 0 until words.size step 2) encipher(p, s, words, j)
        }
        val out = ByteArray(words.size * 4)
        for ((i, word) in words.withIndex()) {
            out[4 * i] = (word ushr 24).toByte()
            out[4 * i + 1] = (word ushr 16).toByte()
            out[4 * i + 2] = (word ushr 8).toByte()
            out[4 * i + 3] = word.toByte()
        }
        return out
    }

    /** Blowfish's key schedule; with [data], bcrypt's variant that mixes the salt into every block. */
    private fun expand(p: IntArray, s: IntArray, key: ByteArray, data: ByteArray?) {
        val keyAt = IntArray(1)
        for (i in p.indices) p[i] = p[i] xor word(key, keyAt)
        val block = IntArray(2)
        val dataAt = IntArray(1)
        for (table in listOf(p, s)) {
            for (i in 0 until table.size step 2) {
                if (data != null) {
                    block[0] = block[0] xor word(data, dataAt)
                    block[1] = block[1] xor word(data, dataAt)
                }
                encipher(p, s, block, 0)
                table[i] = block[0]
                table[i + 1] = block[1]
            }
        }
    }

    /** The next four bytes of [data] as a word, going round to the start at the end. */
    private fun word(data: ByteArray, at: IntArray): Int {
        var word = 0
        var offset = at[0]
        repeat(4) {
            word = (word shl 8) or (data[offset].toInt() and 0xff)
            offset = (offset + 1) % data.size
        }
        at[0] = offset
        return word
    }

    private fun encipher(p: IntArray, s: IntArray, block: IntArray, offset: Int) {
        var left = block[offset] xor p[0]
        var right = block[offset + 1]
        var i = 1
        while (i <= 16) {
            right = right xor f(s, left) xor p[i]
            left = left xor f(s, right) xor p[i + 1]
            i += 2
        }
        block[offset] = right xor p[17]
        block[offset + 1] = left
    }

    private fun f(s: IntArray, x: Int): Int =
        ((s[x ushr 24] + s[0x100 or ((x ushr 16) and 0xff)]) xor s[0x200 or ((x ushr 8) and 0xff)]) + s[0x300 or (x and 0xff)]

    private fun encode(data: ByteArray, length: Int): String {
        val out = StringBuilder()
        var offset = 0
        while (offset < length) {
            var c1 = data[offset++].toInt() and 0xff
            out.append(ALPHABET[(c1 ushr 2) and 0x3f])
            c1 = (c1 and 0x03) shl 4
            if (offset >= length) {
                out.append(ALPHABET[c1 and 0x3f])
                break
            }
            var c2 = data[offset++].toInt() and 0xff
            c1 = c1 or ((c2 ushr 4) and 0x0f)
            out.append(ALPHABET[c1 and 0x3f])
            c1 = (c2 and 0x0f) shl 2
            if (offset >= length) {
                out.append(ALPHABET[c1 and 0x3f])
                break
            }
            c2 = data[offset++].toInt() and 0xff
            c1 = c1 or ((c2 ushr 6) and 0x03)
            out.append(ALPHABET[c1 and 0x3f])
            out.append(ALPHABET[c2 and 0x3f])
        }
        return out.toString()
    }

    private fun decode(text: String, length: Int): ByteArray {
        val out = ArrayList<Byte>(length)
        var offset = 0
        fun next(): Int = ALPHABET.indexOf(text[offset++])
        while (offset < text.length - 1 && out.size < length) {
            val c1 = next()
            val c2 = next()
            out.add(((c1 shl 2) or ((c2 and 0x30) ushr 4)).toByte())
            if (out.size >= length || offset >= text.length) break
            val c3 = next()
            out.add((((c2 and 0x0f) shl 4) or ((c3 and 0x3c) ushr 2)).toByte())
            if (out.size >= length || offset >= text.length) break
            val c4 = next()
            out.add((((c3 and 0x03) shl 6) or c4).toByte())
        }
        return out.toByteArray()
    }

    /** Blowfish starts from the digits of pi after the point: 18 words for its keys, then its four tables of 256. */
    private object Blowfish {
        val INITIAL: IntArray = piFraction(18 + 4 * 256)

        private fun piFraction(words: Int): IntArray {
            val bits = words * 32
            val one = BigInteger.ONE.shiftLeft(bits + GUARD)
            // Machin: pi = 16 atan(1/5) - 4 atan(1/239)
            val pi = arctanOfInverse(5, one).shiftLeft(4) - arctanOfInverse(239, one).shiftLeft(2)
            val fraction = (pi - one * BigInteger.valueOf(3)).shiftRight(GUARD)
            return IntArray(words) { fraction.shiftRight(bits - 32 * (it + 1)).toInt() }
        }

        /** atan(1/x) in fixed point, with [one] as 1. */
        private fun arctanOfInverse(x: Int, one: BigInteger): BigInteger {
            val square = BigInteger.valueOf(x.toLong() * x)
            var power = one / BigInteger.valueOf(x.toLong())
            var sum = BigInteger.ZERO
            var n = 1L
            while (power.signum() != 0) {
                val term = power / BigInteger.valueOf(n)
                sum = if (n % 4 == 1L) sum + term else sum - term
                power /= square
                n += 2
            }
            return sum
        }

        /** Bits beyond the last word, so that the rounding of every term is lost below it. */
        private const val GUARD = 64
    }
}
