package io.github.munzzyy.jackdaw.core.source.web

import java.net.URI
import java.net.URISyntaxException

internal object Urls {
    fun parseHttps(text: String): URI? = try {
        val uri = URI(text.trim())
        if (uri.scheme?.lowercase() == "https" && uri.host != null) uri else null
    } catch (_: URISyntaxException) {
        null
    }

    fun resolveHttps(base: String, reference: String): String? = try {
        val baseUri = URI(base.trim())
        val resolved = baseUri.resolve(reference.trim())
        if (resolved.scheme?.lowercase() == "https" && resolved.host != null) resolved.toString() else null
    } catch (_: Exception) {
        null
    }
}
