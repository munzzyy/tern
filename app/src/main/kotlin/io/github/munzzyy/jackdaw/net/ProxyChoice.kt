package io.github.munzzyy.jackdaw.net

import io.github.munzzyy.jackdaw.engine.ProxyMode
import io.github.munzzyy.jackdaw.engine.Settings
import java.io.IOException
import java.net.Proxy

class ProxySettingsException(message: String) : IOException(message)

object ProxyChoice {
    const val ORBOT_HOST = "127.0.0.1"
    const val ORBOT_PORT = 9050
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /** A proxy the user asked for but that cannot be built fails the request rather than going direct. */
    fun of(settings: Settings): Proxy = when (settings.proxy) {
        ProxyMode.NONE -> Proxy.NO_PROXY
        ProxyMode.ORBOT -> UrlConnectionHttp.socks(ORBOT_HOST, ORBOT_PORT)
        ProxyMode.CUSTOM -> {
            val host = settings.proxyHost.trim()
            if (!HOST.matches(host)) throw ProxySettingsException("The proxy host is not valid")
            if (settings.proxyPort !in 1..65535) throw ProxySettingsException("The proxy port is not valid")
            UrlConnectionHttp.socks(host, settings.proxyPort)
        }
    }
}
