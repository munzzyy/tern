package io.github.munzzyy.tern.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.log.TernLog
import kotlinx.coroutines.launch

/** Where PackageInstaller reports how a session ended. Not exported; only our own PendingIntent reaches it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        TernLog.i(TAG, "Session $sessionId for $appId ended with status $status: $message")
        val engine = RealEngine.obtain(context)
        val done = goAsync()
        // Not a finally: a scope cancelled by a closing engine never runs the block, and Android holds every later broadcast until this one finishes.
        engine.scope.launch {
            engine.installs.onResult(appId, status, sessionId, message, confirm)
        }.invokeOnCompletion { done.finish() }
    }

    companion object {
        const val ACTION = "io.github.munzzyy.tern.INSTALL_RESULT"
        const val EXTRA_APP_ID = "io.github.munzzyy.tern.APP_ID"
        private const val TAG = "TernInstallResult"
    }
}
