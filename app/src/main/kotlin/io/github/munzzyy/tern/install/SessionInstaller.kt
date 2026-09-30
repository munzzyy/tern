package io.github.munzzyy.tern.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.github.munzzyy.tern.log.TernLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException

interface Installer {
    /** Creates a session and writes every file into it. Nothing is installed until [commit]. */
    fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int

    /** The outcome arrives at [InstallReceiver] carrying [appId]. */
    fun commit(appId: String, sessionId: Int)

    fun abandon(sessionId: Int)

    fun liveSessionIds(): Set<Int>

    /** Abandons sessions of ours created more than [maxAgeMs] ago. Returns how many. */
    fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int

    /**
     * The way back to where the person confirms [sessionId], for an installer that knows it
     * without being told again. Android's own installer hands it over once, with its answer.
     */
    fun confirmation(sessionId: Int): Intent? = null

    /** True for an installer that can put the OBB files of an archive in the app's OBB folder. */
    val placesObb: Boolean get() = false

    /**
     * Writes [files] into the OBB folder of [packageName] once [sessionId] installed it, under the
     * names they carry. Throws IOException when a file could not be written.
     */
    fun placeObb(sessionId: Int, packageName: String, files: List<ObbFile>): ObbOutcome = ObbOutcome.CANNOT
}

/** Hands files to Android's PackageInstaller as one session and tells it where to send the answer. */
class SessionInstaller(private val context: Context) : Installer {
    private val installer: PackageInstaller get() = context.packageManager.packageInstaller

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        require(apks.isNotEmpty())
        val sessionId = installer.createSession(sessionParams(packageName, apks.sumOf { it.length() }, claimUpdateOwnership))
        guarded(sessionId) {
            installer.openSession(sessionId).use { it.writeAll(apks) }
        }
        return sessionId
    }

    override fun commit(appId: String, sessionId: Int) = guarded(sessionId) {
        installer.openSession(sessionId).use { it.commit(installResult(context, appId, sessionId).intentSender) }
    }

    override fun abandon(sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (e: SecurityException) {
            TernLog.w(TAG, "Session $sessionId was already gone: ${e.message}")
        }
    }

    override fun liveSessionIds(): Set<Int> = installer.mySessions.mapTo(HashSet()) { it.sessionId }

    override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int {
        var count = 0
        for (session in installer.mySessions) {
            // Android 10 does not say when a session was made, only when it was last written to.
            val since = if (Build.VERSION.SDK_INT >= 30) session.createdMillis else session.updatedMillis
            if (nowMs - since > maxAgeMs) {
                abandon(session.sessionId)
                count++
            }
        }
        return count
    }

    private inline fun guarded(sessionId: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            abandon(sessionId)
            throw e
        } catch (e: RuntimeException) {
            abandon(sessionId)
            throw e
        }
    }

    private companion object {
        const val TAG = "TernInstall"
    }
}

/** What every session of Tern's asks of Android: one app, the size of its files, and the person as the reason. */
internal fun sessionParams(packageName: String, size: Long, claimUpdateOwnership: Boolean): PackageInstaller.SessionParams =
    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(packageName)
        setSize(size)
        setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 34 && claimUpdateOwnership) setRequestUpdateOwnership(true)
    }

/** Writes [apks] into the session unchanged, each one whole and synced, in the order the gate passed them. */
internal fun PackageInstaller.Session.writeAll(apks: List<File>) {
    apks.forEachIndexed { i, apk ->
        FileInputStream(apk).use { input ->
            openWrite("apk-$i.apk", 0, apk.length()).use { out ->
                input.copyTo(out, 64 * 1024)
                fsync(out)
            }
        }
    }
}

/** Where Android sends how [sessionId] ended: to [InstallReceiver], with [appId], and to nothing else. */
internal fun installResult(context: Context, appId: String, sessionId: Int): PendingIntent {
    val intent = Intent(context, InstallReceiver::class.java)
        .setAction(InstallReceiver.ACTION)
        .setPackage(context.packageName)
        .putExtra(InstallReceiver.EXTRA_APP_ID, appId)
    return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
}
