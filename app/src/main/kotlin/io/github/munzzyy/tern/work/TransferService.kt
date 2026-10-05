package io.github.munzzyy.tern.work

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.log.TernLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Keeps the process alive while a download the user started runs, and stops once there is none. */
class TransferService : Service() {
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val engine = RealEngine.obtain(applicationContext)
        val notifier = engine.notifier
        notifier.ensureChannels()
        val notification = notifier.transfer(emptyList(), 0, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.ID_TRANSFER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.ID_TRANSFER, notification)
        }
        if (watcher?.isActive == true) return START_NOT_STICKY
        watcher = engine.scope.launch {
            val pace = Pace(NOTIFY_INTERVAL_MS)
            var before = emptyMap<String, Progress>()
            engine.transfers.takeWhile { it.isNotEmpty() }.collect { transfers ->
                val changed = !Pace.onlyBytes(before, transfers)
                before = transfers
                if (!pace.due(SystemClock.elapsedRealtime(), changed)) return@collect
                // Apps by their names, and files on their way to Downloads by theirs.
                val names = transfers.keys.mapNotNull { id -> engine.apps.value.firstOrNull { it.id == id }?.config?.shownName ?: engine.saves.name(id) }
                val done = transfers.values.sumOf { it.bytesDone }
                val total = transfers.values.takeIf { v -> v.all { it.bytesTotal != null } }?.sumOf { it.bytesTotal ?: 0 }
                notifier.show(Notifier.ID_TRANSFER, notifier.transfer(names, done, total))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Only if nothing asked for the service again in the meantime.
            stopSelf(lastStartId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TernTransfer"

        /** Android drops a notification's updates past five a second, and the one dropped can be the last. */
        private const val NOTIFY_INTERVAL_MS = 1000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TransferService::class.java))
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    TernLog.w(TAG, "Not allowed to start the transfer service now: ${e.message}")
                } else {
                    throw e
                }
            }
        }
    }
}
