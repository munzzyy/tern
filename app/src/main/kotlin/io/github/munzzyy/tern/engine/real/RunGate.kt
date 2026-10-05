package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings

/** Why a background run can reach nothing at all, which it then says once instead of failing every app. */
internal object RunGate {
    enum class Block { OFFLINE, PROXY_SILENT }

    /** [answers] is asked only when there is a network and a proxy on this device to ask. */
    inline fun blocked(online: Boolean, proxyOnDevice: Boolean, answers: () -> Boolean): Block? = when {
        !online -> Block.OFFLINE
        proxyOnDevice && !answers() -> Block.PROXY_SILENT
        else -> null
    }

    /**
     * Only the runs the job starts ask the proxy first. A person who asked for a check sees every
     * app say why it could not be checked, and nothing reads a reason for the whole run.
     */
    fun asksProxyFirst(cause: CheckCause): Boolean = cause == CheckCause.SCHEDULE || cause == CheckCause.RETRY

    /** A proxy that can be asked over 127.0.0.1, so asking sends nothing off the device: Orbot's, or one set there. */
    fun onDevice(settings: Settings): Boolean = when (settings.proxy) {
        ProxyMode.NONE -> false
        ProxyMode.ORBOT -> true
        ProxyMode.CUSTOM -> settings.proxyHost.trim().let { it == "127.0.0.1" || it.equals("localhost", ignoreCase = true) }
    }
}
