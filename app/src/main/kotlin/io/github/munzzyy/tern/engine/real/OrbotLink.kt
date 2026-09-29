package io.github.munzzyy.tern.engine.real

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.net.Orbot
import io.github.munzzyy.tern.net.OrbotWatch
import io.github.munzzyy.tern.net.ProxyProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android side of [OrbotWatch]: the request goes to Orbot's package alone, and the receiver
 * for the answer lives as long as the engine. Any app can send to that receiver, which is why
 * [Orbot.read] decides what is taken from an answer. [installed] stands in for the question
 * whether Orbot is on this device, for tests on a device that has none.
 */
internal class OrbotLink(private val context: Context, scope: CoroutineScope, installed: (() -> Boolean)? = null) {
    private val watch = OrbotWatch(scope, installed ?: ::onThisDevice, ::send, probe = ProxyProbe::answers)

    val state: StateFlow<OrbotState> get() = watch.state

    val port: Int? get() = watch.port

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val answer = try {
                Orbot.read(
                    intent.action,
                    intent.getStringExtra(Orbot.EXTRA_STATUS),
                    intent.getStringExtra(Orbot.EXTRA_SOCKS_PROXY_HOST),
                    intent.getIntExtra(Orbot.EXTRA_SOCKS_PROXY_PORT, NO_PORT),
                )
            } catch (e: RuntimeException) {
                // Extras that cannot be unpacked are thrown at whoever reads the first of them.
                Log.w(TAG, "An answer could not be read: ${e.javaClass.simpleName}")
                null
            }
            if (answer != null) watch.heard(answer)
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Orbot.ACTION_STATUS), ContextCompat.RECEIVER_EXPORTED)
    }

    fun ask() = watch.ask()

    fun packageChanged(packageName: String) {
        if (packageName == Orbot.PACKAGE) watch.packageChanged()
    }

    fun close() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "The receiver was not registered: ${e.message}")
        }
    }

    private fun onThisDevice(): Boolean = try {
        context.packageManager.getPackageInfo(Orbot.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun send() {
        context.sendBroadcast(
            Intent(Orbot.ACTION_START).setPackage(Orbot.PACKAGE).putExtra(Orbot.EXTRA_PACKAGE_NAME, context.packageName),
        )
    }

    private companion object {
        const val TAG = "TernOrbot"
        const val NO_PORT = -1
    }
}
