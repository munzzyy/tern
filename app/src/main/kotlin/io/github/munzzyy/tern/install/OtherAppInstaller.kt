package io.github.munzzyy.tern.install

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.munzzyy.tern.engine.InstallerChoice
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipOutputStream

/**
 * Hands the checked file to another installer app the person chose, such as one that keeps a
 * history or installs for several users. That app always asks, so this never runs in the
 * background. What it was given is exactly what the gate passed, read-only through a content
 * address; whether it installed is read from PackageManager afterwards, as for every install.
 *
 * Sessions here are Tern's own and negative, so they can never be taken for Android's.
 */
class OtherAppInstaller(
    private val context: Context,
    private val target: () -> String?,
    /** The activity of [target] the person picked, for an app with several ways in; null leaves it to the app. */
    private val activity: () -> String? = { null },
) : Installer {
    private val folder = File(context.cacheDir, FOLDER)
    private val next = AtomicInteger(-(System.currentTimeMillis() % 1_000_000).toInt() - 1)

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        require(apks.isNotEmpty())
        target() ?: throw java.io.IOException("No installer app is chosen")
        val sessionId = next.getAndDecrement()
        val dir = File(folder, sessionId.toString()).apply { mkdirs() }
        if (apks.size == 1) {
            apks[0].copyTo(File(dir, "$packageName.apk"), overwrite = true)
        } else {
            // A bundle of split files goes as one .apks, which installer apps take as a whole.
            ZipOutputStream(FileOutputStream(File(dir, "$packageName.apks"))).use { zip ->
                apks.forEachIndexed { i, apk -> zip.putStored(if (i == 0) "base.apk" else "split_$i.apk", apk) }
            }
        }
        return sessionId
    }

    /** The person has to act in the other app, so the answer is "waiting for the user", with the way there. */
    override fun commit(appId: String, sessionId: Int) {
        val handoff = confirmation(sessionId) ?: throw java.io.IOException("The file for the installer app is gone")
        val intent = Intent(context, InstallReceiver::class.java)
            .setAction(InstallReceiver.ACTION)
            .setPackage(context.packageName)
            .putExtra(InstallReceiver.EXTRA_APP_ID, appId)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, handoff)
        context.sendBroadcast(intent)
    }

    override fun confirmation(sessionId: Int): Intent? {
        val app = target() ?: return null
        val file = File(folder, sessionId.toString()).listFiles()?.firstOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val type = if (file.name.endsWith(".apks")) BUNDLE_MIME else APK_MIME
        // The way in the person picked, else the app's own; an app that only installs a package is asked to do that.
        val picked = activity()?.let { ComponentName(app, it) }
        val ways = listOfNotNull(picked).flatMap { component -> ACTIONS.map { Intent(it).setComponent(component) } } + ACTIONS.map { Intent(it) }
        val intent = ways.map { it.setDataAndType(uri, type).setPackage(app) }.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setPackage(app)
        return intent
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun abandon(sessionId: Int) {
        File(folder, sessionId.toString()).deleteRecursively()
    }

    override fun liveSessionIds(): Set<Int> =
        folder.listFiles().orEmpty().mapNotNullTo(HashSet()) { dir -> dir.name.toIntOrNull()?.takeIf { it < 0 && dir.list()?.isNotEmpty() == true } }

    override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int {
        var count = 0
        for (dir in folder.listFiles().orEmpty()) {
            if (nowMs - dir.lastModified() > maxAgeMs) {
                dir.deleteRecursively()
                count++
            }
        }
        return count
    }

    companion object {
        const val FOLDER = "handoff"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val BUNDLE_MIME = "application/zip"

        fun authority(context: Context): String = "${context.packageName}.handoff"

        /** What an installer app is asked, in this order: to open the file, which most take, then to install a package, which some take only. */
        @Suppress("DEPRECATION") // Deprecated for installing through Android; still how some installer apps take a file.
        private val ACTIONS = listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)

        /** True for the way into another installer app that [confirmation] makes, and for nothing else. */
        fun isHandoff(context: Context, intent: Intent): Boolean =
            intent.action in ACTIONS && intent.data?.authority == authority(context) && intent.`package` != null

        /** Every way into an app that says it installs an APK handed to it, Tern itself left out. See [InstallerChoices.of]. */
        fun candidates(context: Context): List<InstallerChoice> {
            val pm = context.packageManager
            val found = ACTIONS.flatMap { action ->
                val probe = Intent(action).setDataAndType(Uri.parse("content://${authority(context)}/x.apk"), APK_MIME)
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { it.activityInfo }
            }
            return InstallerChoices.of(
                found.filter { it.packageName != context.packageName }.map { info ->
                    InstallerChoice(info.packageName, info.applicationInfo.loadLabel(pm).toString().take(100), info.name, info.loadLabel(pm).toString().take(100))
                },
            )
        }

        /** The icon of [choice]: the one its way in shows, else the app's. */
        fun icon(context: Context, choice: InstallerChoice): Drawable? = try {
            val pm = context.packageManager
            choice.activity?.let { pm.getActivityIcon(ComponentName(choice.packageName, it)) } ?: pm.getApplicationIcon(choice.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

/** The installer apps to pick from, kept apart from Android so that the order can be tested. */
object InstallerChoices {
    /**
     * [found] as the picker lists it: apps by name, each way into an app once. An app with one way
     * in has one choice that names none, so the app keeps to its own; one with several has a choice
     * for each, by the names the app gives them.
     */
    fun of(found: List<InstallerChoice>): List<InstallerChoice> =
        found.distinctBy { it.packageName to it.activity }
            .groupBy { it.packageName }
            .values
            .flatMap { ways -> ways.singleOrNull()?.let { listOf(it.copy(activity = null, activityLabel = null)) } ?: ways.sortedBy { (it.activityLabel ?: it.activity).orEmpty().lowercase() } }
            .sortedWith(compareBy<InstallerChoice> { it.label.lowercase() }.thenBy { it.packageName })

    /** The choice the settings name: the way in they name, else the app, else nothing when the app is gone. */
    fun picked(choices: List<InstallerChoice>, packageName: String?, activity: String?): InstallerChoice? {
        val ofApp = choices.filter { it.packageName == packageName }
        return ofApp.firstOrNull { it.activity == activity } ?: ofApp.firstOrNull()
    }
}
