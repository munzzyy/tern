package io.github.munzzyy.tern.engine.real

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.handoff.HandoffItem
import io.github.munzzyy.tern.core.handoff.HandoffLimits
import io.github.munzzyy.tern.core.handoff.HandoffServer
import io.github.munzzyy.tern.core.handoff.LocalAddress
import io.github.munzzyy.tern.core.qr.QrEncoder
import io.github.munzzyy.tern.engine.Handoff
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.QrCode
import io.github.munzzyy.tern.engine.Received
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The sentences a handoff can fail with, kept apart from Android so that the rest runs on the JVM. */
interface HandoffTexts {
    fun noLocalNetwork(): String
    fun behindVpn(): String
    fun cannotOpen(detail: String?): String
    fun notOnScreen(): String
}

/** Where this device is on the network, as far as a handoff cares. */
sealed interface LocalNetwork {
    class At(val address: InetAddress) : LocalNetwork

    data object None : LocalNetwork

    /** The traffic of this app goes through a VPN, so what a phone sends to the local address gets no answer. */
    data object Vpn : LocalNetwork
}

/**
 * Opens and closes the handoff and keeps [handoff] and [handoffEnd] in step with it. What has arrived
 * stays until it is taken, until [close], or until the next handoff opens, also when the handoff has
 * ended by itself.
 */
internal class Handoffs(
    private val network: () -> LocalNetwork,
    private val texts: HandoffTexts,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val limits: HandoffLimits = HandoffLimits(),
) {
    private val state = MutableStateFlow<Handoff?>(null)
    val handoff: StateFlow<Handoff?> = state.asStateFlow()

    private val ended = MutableStateFlow<HandoffEnd?>(null)

    /** Why the last handoff ended. Null while one is open, and before the first. */
    val handoffEnd: StateFlow<HandoffEnd?> = ended.asStateFlow()

    private val lock = Any()
    private var server: HandoffServer? = null
    private var qr: QrCode? = null
    private var left = false
    private var onScreen = true
    private var stopWatching: () -> Unit = {}

    suspend fun open(): Problem? = withContext(Dispatchers.IO) {
        val address = when (val found = network()) {
            is LocalNetwork.At -> found.address
            LocalNetwork.None -> return@withContext Problem(ProblemKind.NETWORK, texts.noLocalNetwork())
            LocalNetwork.Vpn -> return@withContext Problem(ProblemKind.NETWORK, texts.behindVpn())
        }
        val wasOpen = synchronized(lock) {
            if (!onScreen) return@withContext Problem(ProblemKind.UNSUPPORTED, texts.notOnScreen())
            (server?.isOpen == true).also { drop() }
        }
        // Outside the lock: leaving the screen has to be able to close the port while this one waits for the system.
        val opened = try {
            HandoffServer.open(address, limits, nowMs = nowMs, onChange = ::publish)
        } catch (e: IOException) {
            if (wasOpen) ended.value = HandoffEnd.CLOSED
            return@withContext Problem(ProblemKind.NETWORK, texts.cannotOpen(e.message))
        }
        val squares = QrEncoder.encode(opened.addressWithCode)
        synchronized(lock) {
            if (!onScreen) {
                opened.close()
                if (wasOpen) ended.value = HandoffEnd.CLOSED
                return@withContext Problem(ProblemKind.UNSUPPORTED, texts.notOnScreen())
            }
            drop()
            server = opened
            qr = QrCode(squares.size, squares.squares())
            left = false
            publish()
        }
        null
    }

    fun close() = synchronized(lock) {
        if (server?.isOpen == true) ended.value = HandoffEnd.CLOSED
        drop()
        publish()
    }

    fun take(): List<Received> = synchronized(lock) {
        val taken = server?.take().orEmpty()
        publish()
        taken.map {
            when (it) {
                is HandoffItem.Link -> Received.Link(it.text)
                is HandoffItem.ExportFile -> Received.ExportFile(it.name, it.bytes)
            }
        }
    }

    /** For the end of the engine: closes the handoff and stops looking at the screens. */
    fun shutDown() {
        close()
        stopWatching()
    }

    fun cameOnScreen() = synchronized(lock) { onScreen = true }

    /** Ends the handoff. What has arrived stays, for the moment Tern is on the screen again. */
    fun leftScreen() = synchronized(lock) {
        onScreen = false
        left = server?.isOpen == true
        server?.close()
        publish()
    }

    private fun drop() {
        val old = server
        server = null
        qr = null
        old?.close()
    }

    private fun publish() = synchronized(lock) {
        val current = server
        val squares = qr
        val end = current?.end
        if (current != null) {
            ended.value = when (end) {
                null -> null
                HandoffServer.End.EXPIRED -> HandoffEnd.EXPIRED
                HandoffServer.End.USED_UP -> HandoffEnd.USED_UP
                HandoffServer.End.CLOSED -> if (left) HandoffEnd.LEFT_SCREEN else HandoffEnd.CLOSED
            }
        }
        state.value = if (current == null || squares == null || end != null) null else Handoff(address = current.address, code = current.code, qr = squares, closesAtMs = current.closesAtMs, waiting = current.waiting())
    }

    companion object {
        /** The handoff of [e], which ends when the last screen of Tern is stopped. */
        fun on(e: RealEngine): Handoffs {
            val handoffs = Handoffs({ localNetwork(e.context) }, Sentences(e.context), e.nowMs)
            val application = e.context as? Application ?: return handoffs
            val screens = Screens(handoffs)
            application.registerActivityLifecycleCallbacks(screens)
            handoffs.stopWatching = { application.unregisterActivityLifecycleCallbacks(screens) }
            return handoffs
        }

        private fun localNetwork(context: Context): LocalNetwork {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val active = connectivity.activeNetwork ?: return LocalNetwork.None
            val capabilities = connectivity.getNetworkCapabilities(active) ?: return LocalNetwork.None
            val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (vpn) return LocalNetwork.Vpn
            // A mobile network hands out private addresses too, and nothing on it is a phone in the same room.
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) return LocalNetwork.None
            val addresses = connectivity.getLinkProperties(active)?.linkAddresses.orEmpty().map { it.address }
            return LocalAddress.pick(addresses)?.let { LocalNetwork.At(it) } ?: LocalNetwork.None
        }
    }

    private class Sentences(private val context: Context) : HandoffTexts {
        override fun noLocalNetwork() = context.getString(R.string.engine_handoff_no_local_network)
        override fun behindVpn() = context.getString(R.string.engine_handoff_behind_vpn)
        override fun cannotOpen(detail: String?) =
            if (detail.isNullOrBlank()) context.getString(R.string.engine_handoff_cannot_open_plain) else context.getString(R.string.engine_handoff_cannot_open, detail.take(200))
        override fun notOnScreen() = context.getString(R.string.engine_handoff_not_on_screen)
    }

    /** Counts the screens of Tern that are started. A screen that is only being rebuilt, as on rotation, does not count as gone. */
    private class Screens(private val handoffs: Handoffs) : Application.ActivityLifecycleCallbacks {
        private val started = HashSet<Int>()

        override fun onActivityStarted(activity: Activity) {
            synchronized(started) { started += System.identityHashCode(activity) }
            handoffs.cameOnScreen()
        }

        override fun onActivityStopped(activity: Activity) {
            val none = synchronized(started) {
                started -= System.identityHashCode(activity)
                started.isEmpty()
            }
            if (none && !activity.isChangingConfigurations) handoffs.leftScreen()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
