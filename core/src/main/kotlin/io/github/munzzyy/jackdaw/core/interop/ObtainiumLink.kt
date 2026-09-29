package io.github.munzzyy.jackdaw.core.interop

import java.net.URI

sealed interface ObtainiumLink {
    data class Add(val url: String) : ObtainiumLink
    data class App(val json: String) : ObtainiumLink
    data class Apps(val json: String) : ObtainiumLink

    companion object {
        fun parse(uri: String): ObtainiumLink? {
            val parsed = try {
                URI(uri.trim())
            } catch (_: Exception) {
                return null
            }
            if (!parsed.scheme.equals("obtainium", ignoreCase = true)) return null
            val action = parsed.host?.lowercase() ?: return null
            val fromQuery = parsed.rawQuery?.let { queryParam(it, "url") }
            val fromPath = parsed.rawPath?.takeIf { it.length > 1 }?.let { percentDecode(it.substring(1)) }
            val data = fromQuery ?: fromPath ?: ""
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
