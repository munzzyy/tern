package io.github.munzzyy.tern.core.interop

sealed interface ObtainiumLink {
    data class Add(val url: String) : ObtainiumLink
    data class App(val json: String) : ObtainiumLink
    data class Apps(val json: String) : ObtainiumLink

    companion object {
        private const val PREFIX = "obtainium://"
        private const val MAX_LENGTH = 200_000

        /**
         * Obtainium's web page that hands the obtainium:// link after `r=` on to whichever app
         * opens such links. Chat apps show a web address as a link, and a custom scheme as text.
         */
        const val WEB_REDIRECT = "https://apps.obtainium.imranr.dev/redirect?r="

        private val REDIRECT = Regex("^https://apps\\.obtainium\\.imranr\\.dev/redirect/?\\?(.*)$", RegexOption.IGNORE_CASE)

        /**
         * Read by hand: payloads arrive with raw braces and quotes that a strict URI parser refuses.
         * A link behind [WEB_REDIRECT] is read as the link it carries; the page itself is never asked.
         */
        fun parse(uri: String): ObtainiumLink? {
            val text = carried(uri.trim()) ?: return null
            if (text.length > MAX_LENGTH || !text.startsWith(PREFIX, ignoreCase = true)) return null
            val rest = text.substring(PREFIX.length)
            val action = rest.substringBefore('/').substringBefore('?').lowercase()
            val afterAction = rest.substring(action.length)
            val data = when {
                afterAction.startsWith("/") && afterAction.length > 1 -> percentDecode(afterAction.substring(1))
                afterAction.startsWith("?") -> queryParam(afterAction.substring(1), "url") ?: ""
                else -> ""
            }
            return when (action) {
                "add" -> Add(data)
                "app" -> App(data)
                "apps" -> Apps(data)
                else -> null
            }
        }

        /** [text] as it is, or the obtainium:// link that Obtainium's web page carries in it; null when that page carries none. */
        private fun carried(text: String): String? {
            val query = REDIRECT.matchEntire(text)?.groupValues?.get(1) ?: return text
            val raw = query.split('&').firstOrNull { it.startsWith("r=") }?.removePrefix("r=") ?: return null
            // Obtainium leaves the link after r= as it is and only its payload encoded; some pages encode the whole of it.
            return if (raw.startsWith(PREFIX, ignoreCase = true)) raw else percentDecode(raw).takeIf { it.startsWith(PREFIX, ignoreCase = true) }
        }

        private fun queryParam(rawQuery: String, name: String): String? {
            for (pair in rawQuery.split('&')) {
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2 && parts[0] == name) return percentDecode(parts[1])
            }
            return null
        }

        private fun percentDecode(text: String): String {
            val out = java.io.ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (value != null) {
                        out.write(value)
                        i += 3
                        continue
                    }
                }
                if (c.code < 128) out.write(c.code) else out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
