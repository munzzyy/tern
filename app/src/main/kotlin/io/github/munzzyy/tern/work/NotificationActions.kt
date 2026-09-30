package io.github.munzzyy.tern.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.log.TernLog
import kotlinx.coroutines.launch

/**
 * What the buttons of a notification do: update one app, or every app with an update, or stop the
 * downloads under way. The same checks run as when the buttons in Tern are pressed; nothing is
 * installed that would not be there.
 */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_CANCEL_DOWNLOADS) {
            val engine = RealEngine.obtain(context)
            val done = goAsync()
            engine.scope.launch {
                try {
                    engine.ready()
                    engine.cancelDownloads()
                } finally {
                    done.finish()
                }
            }
            return
        }
        if (action != ACTION_UPDATE && action != ACTION_UPDATE_ALL) return
        val appId = intent.getStringExtra(EXTRA_APP)?.take(MAX_ID)
        context.getSystemService(NotificationManager::class.java).cancel(Notifier.ID_UPDATES)
        val engine = RealEngine.obtain(context)
        val done = goAsync()
        engine.scope.launch {
            try {
                engine.ready()
                if (action == ACTION_UPDATE && appId != null) engine.install(appId) else engine.installAllUpdates()
            } catch (e: RuntimeException) {
                TernLog.w(TAG, "The update asked for from a notification did not start: ${e.javaClass.simpleName}")
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val ACTION_UPDATE = "io.github.munzzyy.tern.action.UPDATE"
        private const val ACTION_UPDATE_ALL = "io.github.munzzyy.tern.action.UPDATE_ALL"
        private const val ACTION_CANCEL_DOWNLOADS = "io.github.munzzyy.tern.action.CANCEL_DOWNLOADS"
        private const val EXTRA_APP = "app"
        private const val MAX_ID = 64
        private const val TAG = "TernNotifyAction"

        /** Updates [appId], or every app with an update when it is null. */
        fun update(context: Context, appId: String?): PendingIntent {
            val intent = Intent(context, NotificationActions::class.java)
                .setAction(if (appId == null) ACTION_UPDATE_ALL else ACTION_UPDATE)
                .apply { if (appId != null) putExtra(EXTRA_APP, appId) }
            return PendingIntent.getBroadcast(context, appId?.hashCode() ?: 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Stops every download the person started that has not reached the installer yet. */
        fun cancelDownloads(context: Context): PendingIntent {
            val intent = Intent(context, NotificationActions::class.java).setAction(ACTION_CANCEL_DOWNLOADS)
            return PendingIntent.getBroadcast(context, CANCEL_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private const val CANCEL_REQUEST = 0x7e57
    }
}
