package io.github.munzzyy.stamp.net

import io.github.munzzyy.stamp.engine.OrbotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orbot as another app sees it. Every name and value is Orbot's own: the actions, the two extras
 * for the package and the status and the four status values are constants of TorService in
 * tor-android 0.4.9.11, the library Orbot is built with; the extras for the proxy and the fifth
 * status are in Orbot's OrbotConstants.kt. StartTorReceiver.kt takes the request and
 * OrbotService.replyWithStatus sends the answer.
 */
object Orbot {
    const val PACKAGE = "org.torproject.android"
    const val ACTION_START = "org.torproject.android.intent.action.START"
    const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
    const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
    const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"

    const val STATUS_ON = "ON"
    const val STATUS_OFF = "OFF"
    const val STATUS_STARTING = "STARTING"
    const val STATUS_STOPPING = "STOPPING"

    /** The user of Orbot has switched off that other apps start it. */
    const val STATUS_STARTS_DISABLED = "STARTS_DISABLED"

    /** The only host a proxy of Orbot's is ever taken to be on. */
    const val HOST = "127.0.0.1"

    /** What Stamp takes from an answer. [port] is null unless Orbot is on and named one on this device. */
    data class Answer(val state: OrbotState, val port: Int?)

    /**
     * Reads an answer from its plain values. Any app on the device can send one, so nothing is
     * taken from it but the status and a port on [HOST]. Null when it is no answer of Orbot's.
     */
    fun read(action: String?, status: String?, host: String?, port: Int?): Answer? {
        if (action != ACTION_STATUS) return null
        val state = when (status) {
            STATUS_ON -> OrbotState.ON
            STATUS_STARTING -> OrbotState.STARTING
            STATUS_OFF, STATUS_STOPPING, STATUS_STARTS_DISABLED -> OrbotState.OFF
            else -> return null
        }
        val named = port?.takeIf { state == OrbotState.ON && host == HOST && it in 1..65535 }
        return Answer(state, named)
    }
}

/**
 * Finds out how Orbot is doing and keeps what it last found. Orbot took out the way for other
 * apps to ask it on 2026-09-15 (its commit 3ae39bc554), so the proxy itself is asked first: a
 * SOCKS proxy that answers at Orbot's port on this device means Orbot is on. An older Orbot is
 * still sent the request and can name another port. [installed], [send] and [probe] are the
 * three things that need Android or a socket; answers come in through [heard].
 */
class OrbotWatch(
    private val scope: CoroutineScope,
    private val installed: () -> Boolean,
    private val send: () -> Unit,
    private val answerWithinMs: Long = 3_000,
    private val askAgainAfterMs: Long = 3_000,
    private val askAgainTimes: Int = 40,
    private val probe: (port: Int) -> Boolean = { false },
) {
    private val _state = MutableStateFlow(OrbotState.UNKNOWN)
    val state: StateFlow<OrbotState> = _state.asStateFlow()

    /** The port of the proxy, when it answered there or Orbot named it while it was on. */
    @Volatile
    var port: Int? = null
        private set

    private val answers = MutableStateFlow(0L)
    private var asking: Job? = null

    /** Asks now, and again while an older Orbot says it is starting. A proxy that does not answer and an Orbot that says nothing is off. */
    @Synchronized
    fun ask() {
        asking?.cancel()
        if (!installed()) {
            take(OrbotState.NOT_INSTALLED, null)
            return
        }
        var before = answers.value
        asking = scope.launch {
            if (found()) return@launch
            repeat(askAgainTimes) {
                val sent = try {
                    send()
                    true
                } catch (_: RuntimeException) {
                    false
                }
                val answered = sent && withTimeoutOrNull(answerWithinMs) { answers.first { it != before } } != null
                if (!answered) {
                    if (!found()) take(OrbotState.OFF, null)
                    return@launch
                }
                if (_state.value != OrbotState.STARTING) return@launch
                delay(askAgainAfterMs)
                before = answers.value
            }
        }
    }

    /** True, with the state set, when a proxy answers at the port Orbot once named or at its usual one. */
    private suspend fun found(): Boolean {
        val ports = listOfNotNull(port, ProxyChoice.ORBOT_PORT).distinct()
        val at = withContext(Dispatchers.IO) { ports.firstOrNull(probe) } ?: return false
        take(OrbotState.ON, at)
        return true
    }

    /** An answer that arrived. While Orbot is not installed none can be its own, and none is taken. */
    fun heard(answer: Orbot.Answer) {
        if (!installed()) return
        take(answer.state, answer.port)
        answers.update { it + 1 }
    }

    /** Orbot was installed or removed. */
    fun packageChanged() {
        val there = installed()
        if (!there) {
            synchronized(this) { asking?.cancel() }
            take(OrbotState.NOT_INSTALLED, null)
        } else if (_state.value == OrbotState.NOT_INSTALLED) {
            take(OrbotState.UNKNOWN, null)
        }
    }

    @Synchronized
    private fun take(state: OrbotState, port: Int?) {
        this.port = port
        _state.value = state
    }
}
