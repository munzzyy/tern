package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.Urls
import java.net.URLDecoder

/** What a server's headers say about the file behind an address, read without its body. */
data class ServedFile(val name: String, val kind: AssetKind) {
    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        private const val MAX_HEADER = 2048
        private const val MAX_NAME = 200
        private val INSTALLABLE = setOf(AssetKind.APK, AssetKind.BUNDLE)

        /**
         * The installable file an answer carries, or null when its headers do not say it is one.
         * Only Content-Type and Content-Disposition count; an address ending in .apk proves nothing.
         */
        fun announced(headers: Headers, finalUrl: String): ServedFile? {
            val disposition = dispositionName(headers["Content-Disposition"])
            if (disposition != null && Asset.kindOf(disposition) in INSTALLABLE) return ServedFile(disposition, Asset.kindOf(disposition))
            if (!isApkType(headers["Content-Type"])) return null
            val name = disposition ?: lastSegment(finalUrl) ?: "download.apk"
            return ServedFile(name, AssetKind.APK)
        }

        /** The name for a file at [url]: announced by the server, else the final address, else the one asked for. */
        fun of(headers: Headers, finalUrl: String, url: String): ServedFile? {
            announced(headers, finalUrl)?.let { return it }
            for (candidate in listOfNotNull(lastSegment(finalUrl), lastSegment(url))) {
                val kind = Asset.kindOf(candidate)
                if (kind in INSTALLABLE) return ServedFile(candidate, kind)
            }
            return null
        }

        fun isApkType(value: String?): Boolean =
            value != null && value.length <= MAX_HEADER && value.substringBefore(';').trim().equals(APK_TYPE, ignoreCase = true)

        /** The file name in a Content-Disposition header (RFC 6266), preferring filename* over filename. */
        fun dispositionName(value: String?): String? {
            if (value == null || value.length > MAX_HEADER) return null
            val params = parameters(value)
            val extended = params["filename*"]?.let(::decodeExtended)?.let(::clean)
            return extended ?: params["filename"]?.let(::clean)
        }

        private fun parameters(value: String): Map<String, String> {
            val out = HashMap<String, String>()
            var i = value.indexOf(';')
            if (i < 0) return out
            while (i < value.length) {
                i++
                val eq = value.indexOf('=', i)
                if (eq < 0) break
                val semicolon = value.indexOf(';', i)
                if (semicolon in 0 until eq) {
                    i = semicolon
                    continue
                }
                val key = value.substring(i, eq).trim().lowercase()
                i = eq + 1
                val text = StringBuilder()
                if (i < value.length && value[i] == '"') {
                    i++
                    var closed = false
                    while (i < value.length) {
                        val c = value[i]
                        if (c == '\\' && i + 1 < value.length) {
                            text.append(value[i + 1])
                            i += 2
                            continue
                        }
                        if (c == '"') {
                            closed = true
                            i++
                            break
                        }
                        text.append(c)
                        i++
                    }
                    if (!closed) return out
                    while (i < value.length && value[i] != ';') i++
                } else {
                    val end = value.indexOf(';', i).let { if (it < 0) value.length else it }
                    text.append(value, i, end)
                    i = end
                }
                if (key.isNotEmpty() && key !in out) out[key] = text.toString().trim()
            }
            return out
        }

        private fun decodeExtended(value: String): String? {
            val parts = value.split('\'', limit = 3)
            if (parts.size != 3) return null
            val charset = when (parts[0].trim().lowercase()) {
                "utf-8" -> Charsets.UTF_8
                "iso-8859-1" -> Charsets.ISO_8859_1
                else -> return null
            }
            return try {
                URLDecoder.decode(parts[2].replace("+", "%2B"), charset)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun lastSegment(url: String): String? = Urls.segments(url).lastOrNull()?.let(::clean)

        /** Keeps the last path part only, and drops characters that could disguise a name on screen. */
        fun clean(raw: String): String? {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val kept = base.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
            if (kept.isEmpty() || kept == "." || kept == "..") return null
            return kept.takeLast(MAX_NAME)
        }
    }
}
