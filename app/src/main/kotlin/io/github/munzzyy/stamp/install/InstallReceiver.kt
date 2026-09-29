package io.github.munzzyy.stamp.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.munzzyy.stamp.engine.real.RealEngine
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
        Log.i(TAG, "Session $sessionId for $appId ended with status $status: $message")
        val engine = RealEngine.obtain(context)
        val done = goAsync()
        engine.scope.launch {
            try {
                engine.installs.onResult(appId, status, sessionId, message, confirm)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.munzzyy.stamp.INSTALL_RESULT"
        const val EXTRA_APP_ID = "io.github.munzzyy.stamp.APP_ID"
        private const val TAG = "StampInstallResult"
    }
}
