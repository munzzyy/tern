package io.github.munzzyy.tern.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.munzzyy.tern.engine.InstallerChoice
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hands the checked file to another installer app the person chose, such as one that keeps a
 * history or installs for several users. That app always asks, so this never runs in the
 * background. What it was given is exactly what the gate passed, read-only through a content
 * address; whether it installed is read from PackageManager afterwards, as for every install.
 *
 * Sessions here are Tern's own and negative, so they can never be taken for Android's.
 */
class OtherAppInstaller(private val context: Context, private val target: () -> String?) : Installer {
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
        val bundle = file.name.endsWith(".apks")
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (bundle) BUNDLE_MIME else APK_MIME)
            .setPackage(app)
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

        /** True for the way into another installer app that [confirmation] makes, and for nothing else. */
        fun isHandoff(context: Context, intent: Intent): Boolean =
            intent.action == Intent.ACTION_VIEW && intent.data?.authority == authority(context) && intent.`package` != null

        /** Apps that say they install an APK handed to them, Tern itself left out. */
        fun candidates(context: Context): List<InstallerChoice> {
            val pm = context.packageManager
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${authority(context)}/x.apk"), APK_MIME)
            return pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .map { InstallerChoice(it.packageName, it.applicationInfo.loadLabel(pm).toString().take(100)) }
                .sortedBy { it.label.lowercase() }
        }

        private fun ZipOutputStream.putStored(name: String, file: File) {
            val crc = CRC32()
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = file.length()
                compressedSize = file.length()
                this.crc = crc.value
            }
            putNextEntry(entry)
            FileInputStream(file).use { it.copyTo(this, 64 * 1024) }
            closeEntry()
        }
    }
}
