package io.github.munzzyy.jackdaw.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
}

/** Hands files to Android's PackageInstaller as one session and tells it where to send the answer. */
class SessionInstaller(private val context: Context) : Installer {
    private val installer: PackageInstaller get() = context.packageManager.packageInstaller

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        require(apks.isNotEmpty())
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apks.sumOf { it.length() })
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34 && claimUpdateOwnership) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        guarded(sessionId) {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { i, apk ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("apk-$i.apk", 0, apk.length()).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    }
                }
            }
        }
        return sessionId
    }

    override fun commit(appId: String, sessionId: Int) = guarded(sessionId) {
        installer.openSession(sessionId).use { it.commit(resultIntent(appId, sessionId).intentSender) }
    }

    override fun abandon(sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Session $sessionId was already gone: ${e.message}")
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

    private fun resultIntent(appId: String, sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallReceiver::class.java)
            .setAction(InstallReceiver.ACTION)
            .setPackage(context.packageName)
            .putExtra(InstallReceiver.EXTRA_APP_ID, appId)
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private companion object {
        const val TAG = "JackdawInstall"
    }
}
