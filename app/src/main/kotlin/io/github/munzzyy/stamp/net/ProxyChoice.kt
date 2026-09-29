package io.github.munzzyy.stamp.net

import io.github.munzzyy.stamp.engine.ProxyMode
import io.github.munzzyy.stamp.engine.Settings
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

/**
 * The proxy every request leaves through. It is asked for each request, so a change of the setting
 * holds from the next request on. While there is nobody to ask, a request fails: the transport is
 * made before the engine that knows the setting, and must not go direct in between.
 */
class ProxyDoor {
    @Volatile
    private var ask: (() -> Proxy)? = null

    fun follow(ask: () -> Proxy) {
        this.ask = ask
    }

    @Throws(IOException::class)
    fun proxy(): Proxy = (ask ?: throw ProxySettingsException("The proxy setting is not known yet")).invoke()
}
