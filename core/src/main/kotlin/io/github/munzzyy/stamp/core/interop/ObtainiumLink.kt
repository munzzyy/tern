package io.github.munzzyy.stamp.core.interop

sealed interface ObtainiumLink {
    data class Add(val url: String) : ObtainiumLink
    data class App(val json: String) : ObtainiumLink
    data class Apps(val json: String) : ObtainiumLink

    companion object {
        private const val PREFIX = "obtainium://"
        private const val MAX_LENGTH = 200_000

        /** Read by hand: payloads arrive with raw braces and quotes that a strict URI parser refuses. */
        fun parse(uri: String): ObtainiumLink? {
            val text = uri.trim()
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
