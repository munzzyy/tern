package io.github.munzzyy.tern.net

import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings
import java.io.IOException
import java.net.Proxy

class ProxySettingsException(message: String) : IOException(message)

/** A request through a proxy failed, and the proxy did not answer when it was asked. Nothing went round it. */
class ProxySilentException(cause: IOException) : IOException("The proxy did not answer", cause)

fun Throwable.isProxySilent(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is ProxySilentException }

object ProxyChoice {
    const val ORBOT_HOST = "127.0.0.1"
    const val ORBOT_PORT = 9050
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /**
     * A proxy the user asked for but that cannot be built fails the request rather than going
     * direct. [orbotPort] is the port Orbot reported, if it reported one: Orbot takes another
     * port when its own is taken. The host of Orbot's proxy is always this device.
     */
    fun of(settings: Settings, orbotPort: Int? = null): Proxy = when (settings.proxy) {
        ProxyMode.NONE -> Proxy.NO_PROXY
        ProxyMode.ORBOT -> UrlConnectionHttp.socks(ORBOT_HOST, orbotPort?.takeIf { it in 1..65535 } ?: ORBOT_PORT)
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
