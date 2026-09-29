package io.github.munzzyy.jackdaw.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.real.Texts

class Notifier(context: Context, private val texts: Texts) {
    private val c = context.applicationContext
    private val manager = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(UPDATES, texts.channelUpdates(), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(INSTALLED, texts.channelInstalled(), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ATTENTION, texts.channelAttention(), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(TRANSFERS, texts.channelTransfers(), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun updates(names: List<String>) {
        if (names.isEmpty()) return
        post(ID_UPDATES, builder(UPDATES, R.drawable.ic_stat_update).setContentTitle(texts.notifyUpdates(names.size, names.singleOrNull())).setContentText(names.joinToString()))
    }

    fun installed(names: List<String>) {
        if (names.isEmpty()) return
        post(ID_INSTALLED, builder(INSTALLED, R.drawable.ic_stat_done).setContentTitle(texts.notifyInstalled(names.size, names.singleOrNull())).setContentText(names.joinToString()))
    }

    fun failures(names: List<String>) {
        if (names.isEmpty()) return
        post(ID_FAILURES, builder(ATTENTION, R.drawable.ic_stat_attention).setContentTitle(texts.notifyFailures(names.size)).setContentText(names.joinToString()))
    }

    /** The system installer wants the user; tapping opens its confirmation. */
    fun confirm(appId: String, name: String, confirm: Intent) {
        val tap = PendingIntent.getActivity(c, appId.hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = builder(ATTENTION, R.drawable.ic_stat_attention).setContentTitle(texts.notifyConfirm(name)).setContentIntent(tap)
        post(confirmId(appId), n)
    }

    fun cancelConfirm(appId: String) = manager.cancel(confirmId(appId))

    fun transfer(names: List<String>, done: Long, total: Long?): Notification {
        val b = builder(TRANSFERS, R.drawable.ic_stat_download)
            .setContentTitle(texts.notifyDownloading(names.joinToString()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total != null && total > 0) {
            b.setProgress(PROGRESS_SCALE, (done * PROGRESS_SCALE / total).toInt().coerceIn(0, PROGRESS_SCALE), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun builder(channel: String, icon: Int): Notification.Builder {
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.let { PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(c, channel).setSmallIcon(icon).setAutoCancel(true).also { b -> open?.let(b::setContentIntent) }
    }

    private fun post(id: Int, builder: Notification.Builder) = show(id, builder.build())

    fun show(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification not allowed: ${e.message}")
        }
    }

    private fun confirmId(appId: String) = ID_CONFIRM_BASE + (appId.hashCode() and 0xffff)

    companion object {
        const val UPDATES = "updates"
        const val INSTALLED = "installed"
        const val ATTENTION = "attention"
        const val TRANSFERS = "transfers"
        const val ID_UPDATES = 1
        const val ID_INSTALLED = 2
        const val ID_FAILURES = 3
        const val ID_TRANSFER = 4
        private const val ID_CONFIRM_BASE = 0x10000
        private const val PROGRESS_SCALE = 1000
        private const val TAG = "JackdawNotify"
    }
}
