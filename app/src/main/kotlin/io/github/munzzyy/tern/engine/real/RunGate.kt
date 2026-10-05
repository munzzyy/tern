package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.CheckCause
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Whether a background run can reach anything before it checks a single app. */
internal object RunGate {
    /** [answers] is asked only when there is a network and a proxy on this device to ask. */
    inline fun blocked(online: Boolean, proxyOnDevice: Boolean, answers: () -> Boolean): RunStop? = when {
        !online -> RunStop.OFFLINE
        proxyOnDevice && !answers() -> RunStop.PROXY_SILENT
        else -> null
    }

    /**
     * Whether the proxy answers now or, when it is Orbot, comes up within [waitMs] of being asked
     * to start. [start] asks Orbot and says whether it is on this device; one that is not is not
     * waited for.
     */
    suspend fun answersInTime(probe: () -> Boolean, orbot: Boolean, start: () -> Boolean, waitMs: Long, pollMs: Long): Boolean {
        if (probe()) return true
        if (!orbot || !start()) return false
        return withTimeoutOrNull(waitMs) {
            while (!probe()) delay(pollMs)
            true
        } ?: false
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
