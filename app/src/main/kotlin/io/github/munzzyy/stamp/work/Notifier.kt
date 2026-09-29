package io.github.munzzyy.stamp.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.real.Texts

/**
 * Every notification has a version without names. It is what a lock screen set to hide sensitive
 * content shows, and the only version there is while [names] says no: Android shows the whole
 * notification on a lock screen that is not set that way.
 */
class Notifier(context: Context, private val texts: Texts, private val names: () -> Boolean) {
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
        show(ID_UPDATES, updatesAbout(names))
    }

    fun installed(names: List<String>) {
        if (names.isEmpty()) return
        show(ID_INSTALLED, installedAbout(names))
    }

    fun failures(names: List<String>) {
        if (names.isEmpty()) return
        show(ID_FAILURES, failuresAbout(names))
    }

    /** The system installer wants the user; tapping opens its confirmation. */
    fun confirm(appId: String, name: String, confirm: Intent) {
        val tap = PendingIntent.getActivity(c, appId.hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        show(confirmId(appId), confirmAbout(name) { it.setContentIntent(tap) })
    }

    fun cancelConfirm(appId: String) = manager.cancel(confirmId(appId))

    fun transfer(apps: List<String>, done: Long, total: Long?): Notification =
        about(TRANSFERS, R.drawable.ic_stat_download, texts.notifyDownloadingPlain(), apps.takeIf { it.isNotEmpty() }?.let { texts.notifyDownloading(it.joinToString()) }, null) { b ->
            b.setOngoing(true).setOnlyAlertOnce(true)
            if (total != null && total > 0) {
                b.setProgress(PROGRESS_SCALE, (done * PROGRESS_SCALE / total).toInt().coerceIn(0, PROGRESS_SCALE), false)
            } else {
                b.setProgress(0, 0, true)
            }
        }

    fun updatesAbout(apps: List<String>): Notification =
        about(UPDATES, R.drawable.ic_stat_update, texts.notifyUpdates(apps.size, null), texts.notifyUpdates(apps.size, apps.singleOrNull()), apps.joinToString())

    fun installedAbout(apps: List<String>): Notification =
        about(INSTALLED, R.drawable.ic_stat_done, texts.notifyInstalled(apps.size, null), texts.notifyInstalled(apps.size, apps.singleOrNull()), apps.joinToString())

    fun failuresAbout(apps: List<String>): Notification =
        about(ATTENTION, R.drawable.ic_stat_attention, texts.notifyFailures(apps.size), texts.notifyFailures(apps.size), apps.joinToString())

    fun confirmAbout(app: String, more: (Notification.Builder) -> Unit = {}): Notification =
        about(ATTENTION, R.drawable.ic_stat_attention, texts.notifyConfirmPlain(), texts.notifyConfirm(app), null, more)

    private fun about(channel: String, icon: Int, plain: String, named: String?, text: String?, more: (Notification.Builder) -> Unit = {}): Notification {
        val public = builder(channel, icon).setContentTitle(plain).setVisibility(Notification.VISIBILITY_PUBLIC).also(more)
        if (named == null || !names()) return public.build()
        return builder(channel, icon)
            .setContentTitle(named)
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public.build())
            .also(more)
            .build()
    }

    private fun builder(channel: String, icon: Int): Notification.Builder {
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.let { PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(c, channel).setSmallIcon(icon).setAutoCancel(true).also { b -> open?.let(b::setContentIntent) }
    }

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
        private const val TAG = "StampNotify"
    }
}
