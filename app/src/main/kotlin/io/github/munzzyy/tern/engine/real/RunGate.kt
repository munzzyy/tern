package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings

/** Whether a background run can reach anything before it checks a single app. */
internal object RunGate {
    /** [answers] is asked only when there is a network and a proxy on this device to ask. */
    inline fun blocked(online: Boolean, proxyOnDevice: Boolean, answers: () -> Boolean): RunStop? = when {
        !online -> RunStop.OFFLINE
        proxyOnDevice && !answers() -> RunStop.PROXY_SILENT
        else -> null
    }

    /**
     * Whether the job started the run. Only those ask the proxy first, and only theirs are noted for
     * settings. A person who asked for a check sees every app say why it could not be checked.
     */
    fun byTheJob(cause: CheckCause): Boolean = cause == CheckCause.SCHEDULE || cause == CheckCause.RETRY

    /** A proxy that can be asked over 127.0.0.1, so asking sends nothing off the device: Orbot's, or one set there. */
    fun onDevice(settings: Settings): Boolean = when (settings.proxy) {
        ProxyMode.NONE -> false
        ProxyMode.ORBOT -> true
        ProxyMode.CUSTOM -> settings.proxyHost.trim().let { it == "127.0.0.1" || it.equals("localhost", ignoreCase = true) }
    }
}
