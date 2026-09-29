package io.github.munzzyy.jackdaw.core.source.fdroid

import java.net.URI
import java.net.URISyntaxException

internal object Urls {
    fun parseHttps(text: String): URI? = try {
        val uri = URI(text.trim())
        if (uri.scheme?.lowercase() == "https" && uri.host != null) uri else null
    } catch (_: URISyntaxException) {
        null
    }
}
