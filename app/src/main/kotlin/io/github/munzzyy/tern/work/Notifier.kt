package io.github.munzzyy.tern.work

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.SavedFile
import io.github.munzzyy.tern.engine.real.Texts
import io.github.munzzyy.tern.log.TernLog

/** An app that was installed: [version] as it was installed, and [update] when it was on the device before. */
data class Installed(val id: String, val name: String, val version: String?, val update: Boolean = true)

/** An app that could not be checked or updated, and why, in the words shown for it. */
data class Trouble(val id: String, val name: String, val reason: String)

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
                NotificationChannel(TRACKED, texts.channelTracked(), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHECKING, texts.channelChecking(), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(SAVED, texts.channelSaved(), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /**
     * [apps] are ids and names. A notification about one app opens its page and offers to update
     * it; one about several offers to update them all. Either way nothing starts until it is tapped.
     */
    fun updates(apps: List<Pair<String, String>>, quiet: Boolean = false) {
        if (apps.isEmpty()) return
        val single = apps.singleOrNull()
        show(
            ID_UPDATES,
            updatesAbout(apps.map { it.second }) { b ->
                // Which apps it names, so that an install can take its app out of it later.
                b.addExtras(Bundle().apply { putStringArray(EXTRA_APPS, apps.map { it.first }.toTypedArray()) })
                if (quiet) b.setOnlyAlertOnce(true)
                if (single != null) b.setContentIntent(openApp(single.first))
                val label = if (single != null) texts.actionUpdate() else texts.actionUpdateAll()
                b.addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_update), label, NotificationActions.update(c, single?.first)).build())
            },
        )
    }

    /**
     * Takes [appId], now installed, out of the notification of updates, if one shows: it then names
     * the apps that still wait, or goes when none does. [waiting] gives the name of an app whose
     * update still waits, and null for one whose does not. A notification that was swiped away
     * stays away.
     */
    fun installedUpdate(appId: String, waiting: (String) -> String?) {
        val shown = try {
            manager.activeNotifications.firstOrNull { it.id == ID_UPDATES }?.notification
        } catch (e: RuntimeException) {
            TernLog.i(TAG, "Could not read the notifications shown: ${e.message}")
            null
        } ?: return
        val named = shown.extras.getStringArray(EXTRA_APPS)?.toList().orEmpty()
        val left = stillWaiting(named, appId, waiting)
        if (left.isEmpty()) manager.cancel(ID_UPDATES) else updates(left, quiet = true)
    }

    /** New releases of apps that are only tracked, apart from the updates Tern can install. */
    fun tracked(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) return
        val single = apps.singleOrNull()
        val names = apps.map { it.second }
        show(
            ID_TRACKED,
            about(TRACKED, R.drawable.ic_stat_update, texts.notifyTracked(names.size, null), texts.notifyTracked(names.size, names.singleOrNull()), names.joinToString()) { b ->
                if (single != null) b.setContentIntent(openApp(single.first))
            },
        )
    }

    /** Shown, quietly, while a background check runs, when the settings ask for it. */
    fun checking(count: Int) = show(
        ID_CHECKING,
        about(CHECKING, R.drawable.ic_stat_update, texts.notifyChecking(count), null, null) { b ->
            b.setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setProgress(0, 0, true)
        },
    )

    fun doneChecking() = manager.cancel(ID_CHECKING)

    /** Opens the page of one app, from a notification about it alone. */
    private fun openApp(appId: String): PendingIntent {
        val open = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()).setPackage(c.packageName)
            .putExtra(EXTRA_OPEN_APP, appId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, appId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Apps that were installed or updated. A notification about one says its version and opens its page. */
    fun installed(apps: List<Installed>) {
        if (apps.isEmpty()) return
        show(ID_INSTALLED, installedAbout(apps) { b -> apps.singleOrNull()?.let { b.setContentIntent(openApp(it.id)) } })
    }

    /**
     * Apps that could not be checked or updated, and why, a line for each reason. A tap opens the
     * page of the one app, or for several a list of their problems as Tern holds them then.
     */
    fun failures(apps: List<Trouble>) {
        if (apps.isEmpty()) return
        val single = apps.singleOrNull()
        show(ID_FAILURES, failuresAbout(apps) { b -> b.setContentIntent(if (single != null) openApp(single.id) else openProblems(apps.map { it.id })) })
    }

    /** Opens Tern on the problems of [appIds]. Only the ids travel, and Tern shows what it holds for them. */
    private fun openProblems(appIds: List<String>): PendingIntent {
        val open = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()).setPackage(c.packageName)
            .putExtra(EXTRA_PROBLEMS, appIds.take(MAX_PROBLEM_APPS).toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, ID_FAILURES, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The file was saved. A tap opens the place Android lists downloads in. */
    fun saved(file: SavedFile) = show(savedId(file.name), savedAbout(file) { b -> openDownloads()?.let(b::setContentIntent) })

    /** The file [name] could not be saved, said for a person who may have left the page that asked for it. */
    fun notSaved(name: String, reason: String) = show(savedId(name), notSavedAbout(name, reason))

    /**
     * Android's list of downloads, where Download/Tern is. Never the file itself: a saved APK was
     * not checked, and opening it would hand it to an installer.
     */
    private fun openDownloads(): PendingIntent? {
        val view = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(c.packageManager) == null) return null
        return PendingIntent.getActivity(c, ID_SAVED_BASE, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun savedId(name: String) = ID_SAVED_BASE + (name.hashCode() and 0xffff)

    /** The system installer wants the user; tapping opens its confirmation. */
    fun confirm(appId: String, name: String, confirm: Intent) {
        val tap = PendingIntent.getActivity(c, appId.hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        show(confirmId(appId), confirmAbout(name) { it.setContentIntent(tap) })
    }

    fun cancelConfirm(appId: String) = manager.cancel(confirmId(appId))

    /** The downloads the person started: how far they are, in bytes too, and a way to stop them all. */
    fun transfer(apps: List<String>, done: Long, total: Long?): Notification =
        about(TRANSFERS, R.drawable.ic_stat_download, texts.notifyDownloadingPlain(), apps.takeIf { it.isNotEmpty() }?.let { texts.notifyDownloading(it.joinToString()) }, null) { b ->
            b.setOngoing(true).setOnlyAlertOnce(true)
            if (apps.isNotEmpty()) b.setContentText(texts.notifyBytes(done, total?.takeIf { it > 0 }))
            b.addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_download), texts.actionCancel(), NotificationActions.cancelDownloads(c)).build())
            if (total != null && total > 0) {
                b.setProgress(PROGRESS_SCALE, (done * PROGRESS_SCALE / total).toInt().coerceIn(0, PROGRESS_SCALE), false)
            } else {
                b.setProgress(0, 0, true)
            }
        }

    fun updatesAbout(apps: List<String>, more: (Notification.Builder) -> Unit = {}): Notification =
        about(UPDATES, R.drawable.ic_stat_update, texts.notifyUpdates(apps.size, null), texts.notifyUpdates(apps.size, apps.singleOrNull()), apps.joinToString(), more)

    fun installedAbout(apps: List<Installed>, more: (Notification.Builder) -> Unit = {}): Notification {
        val single = apps.singleOrNull()
        val version = single?.version
        val named = if (single == null || version == null) {
            texts.notifyInstalled(apps.size, single?.name)
        } else if (single.update) {
            texts.notifyUpdatedTo(single.name, version)
        } else {
            texts.notifyInstalledAt(single.name, version)
        }
        return about(INSTALLED, R.drawable.ic_stat_done, texts.notifyInstalled(apps.size, null), named, apps.joinToString { it.name }, more)
    }

    fun failuresAbout(apps: List<Trouble>, more: (Notification.Builder) -> Unit = {}): Notification {
        val lines = grouped(apps).joinToString("\n") { (names, reason) -> texts.notifyProblemLine(names.joinToString(), reason) }
        val title = texts.notifyFailures(apps.size)
        return about(ATTENTION, R.drawable.ic_stat_attention, title, title, lines, more) { it.setStyle(Notification.BigTextStyle().bigText(lines)) }
    }

    fun savedAbout(file: SavedFile, more: (Notification.Builder) -> Unit = {}): Notification =
        about(SAVED, R.drawable.ic_stat_done, texts.notifySavedPlain(), texts.notifySaved(file.name), file.place, more)

    fun notSavedAbout(name: String, reason: String): Notification =
        about(SAVED, R.drawable.ic_stat_attention, texts.notifyNotSavedPlain(), texts.notifyNotSaved(name), reason) { it.setStyle(Notification.BigTextStyle().bigText(reason)) }

    fun confirmAbout(app: String, more: (Notification.Builder) -> Unit = {}): Notification =
        about(ATTENTION, R.drawable.ic_stat_attention, texts.notifyConfirmPlain(), texts.notifyConfirm(app), null, more)

    /** [more] goes into both versions; [namedOnly] only into the one that names apps, which a locked screen may hide. */
    private fun about(
        channel: String,
        icon: Int,
        plain: String,
        named: String?,
        text: String?,
        more: (Notification.Builder) -> Unit = {},
        namedOnly: (Notification.Builder) -> Unit = {},
    ): Notification {
        val public = builder(channel, icon).setContentTitle(plain).setVisibility(Notification.VISIBILITY_PUBLIC).also(more)
        if (named == null || !names()) return public.build()
        return builder(channel, icon)
            .setContentTitle(named)
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public.build())
            .also(more)
            .also(namedOnly)
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
            TernLog.i(TAG, "Notification not allowed: ${e.message}")
        }
    }

    private fun confirmId(appId: String) = ID_CONFIRM_BASE + (appId.hashCode() and 0xffff)

    companion object {
        const val UPDATES = "updates"
        const val INSTALLED = "installed"
        const val ATTENTION = "attention"
        const val TRANSFERS = "transfers"
        const val TRACKED = "tracked"
        const val CHECKING = "checking"
        const val SAVED = "saved"
        const val ID_UPDATES = 1
        const val ID_INSTALLED = 2
        const val ID_FAILURES = 3
        const val ID_TRANSFER = 4
        const val ID_TRACKED = 5
        const val ID_CHECKING = 6

        /** The app whose page a notification opens. */
        const val EXTRA_OPEN_APP = "io.github.munzzyy.tern.OPEN_APP"

        /** The apps whose problems a notification opens Tern on, by their ids. */
        const val EXTRA_PROBLEMS = "io.github.munzzyy.tern.PROBLEMS"
        private const val EXTRA_APPS = "io.github.munzzyy.tern.APPS"
        private const val MAX_PROBLEM_APPS = 50
        private const val ID_SAVED_BASE = 0x20000

        /** The reasons of [apps], each once and in the order first met, with the names of the apps that met it. */
        fun grouped(apps: List<Trouble>): List<Pair<List<String>, String>> =
            apps.groupBy({ it.reason }, { it.name }).map { (reason, names) -> names to reason }

        /**
         * The apps of a notification that named [named] which still wait for their update, by id
         * and name, once [installed] is in. An app named there only by an older Tern is not known
         * and so not named again.
         */
        fun stillWaiting(named: List<String>, installed: String, waiting: (String) -> String?): List<Pair<String, String>> =
            named.filter { it != installed }.mapNotNull { id -> waiting(id)?.let { id to it } }
        private const val ID_CONFIRM_BASE = 0x10000
        private const val PROGRESS_SCALE = 1000
        private const val TAG = "TernNotify"
    }
}
