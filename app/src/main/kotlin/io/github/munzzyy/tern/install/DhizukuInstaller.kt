package io.github.munzzyy.tern.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Process
import androidx.annotation.StringRes
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.log.TernLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs through Dhizuku, as Obtainium's installer plugin does. The session is made in Android's
 * own installer, reached through [NonSdkCalls], and Dhizuku makes every call about it, so it
 * belongs to Dhizuku's package, the device owner, and Android installs without a prompt. The files
 * are the ones the gate passed, written by Tern unchanged, and the answer comes to [InstallReceiver]
 * as it does from Android's own installer. Dhizuku owns the session and could change it, so the
 * install counts only once PackageManager shows the certificate the gate verified. Google Play
 * cannot be named as the installer, update ownership cannot be asked for, and with no shell no OBB
 * file is put in place. Nothing is ever handed to another installer: a failure is said in words.
 */
class DhizukuInstaller(
    private val context: Context,
    private val dhizuku: Dhizuku,
    /** The sentence for why an install stopped at Dhizuku, in the language of the person. */
    private val words: (DhizukuState) -> String,
    /** Whether Android still has the session. It does not list a session of Dhizuku's among Tern's own. */
    private val sessionExists: (Int) -> Boolean = { context.packageManager.packageInstaller.getSessionInfo(it) != null },
    /** The user Tern runs as, so an install is for that user as PackageInstaller's would be. */
    private val userId: () -> Int = { Process.myUid() / ShellInstaller.PER_USER_RANGE },
) : Installer {
    /** Sessions this process made through Dhizuku, by when. Android does not list them among Tern's own. */
    private val made = ConcurrentHashMap<Int, Long>()

    /** How Dhizuku stands with Tern now, this Android included. Asks Dhizuku, so never on the main thread. */
    fun state(): DhizukuState = if (NonSdkCalls.available) dhizuku.state() else DhizukuState.UNSUPPORTED

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        require(apks.isNotEmpty())
        val sessionId = inWords {
            val calls = NonSdkCalls.get()
            val owner = dhizuku.ready()
            // Android names the package that makes the session as its installer, and installs without a prompt only for the device owner.
            val installer = calls.packageInstaller(calls.installer(dhizuku::wrap), owner, userId())
            installer.createSession(sessionParams(packageName, apks.sumOf { it.length() }, claimUpdateOwnership = false))
        }
        made[sessionId] = System.currentTimeMillis()
        try {
            inWords { open(sessionId).use { it.writeAll(apks) } }
        } catch (e: IOException) {
            abandon(sessionId)
            throw e
        } catch (e: RuntimeException) {
            abandon(sessionId)
            throw e
        }
        return sessionId
    }

    /** Android answers once it is done, at [InstallReceiver], as it answers its own installer. */
    override fun commit(appId: String, sessionId: Int) = inWords {
        dhizuku.ready()
        open(sessionId).use { it.commit(installResult(context, appId, sessionId).intentSender) }
    }

    override fun abandon(sessionId: Int) {
        made.remove(sessionId)
        try {
            inWords {
                val calls = NonSdkCalls.get()
                val owner = dhizuku.owner() ?: throw DhizukuException(DhizukuState.NOT_OWNER)
                calls.packageInstaller(calls.installer(dhizuku::wrap), owner, userId()).abandonSession(sessionId)
            }
        } catch (e: IOException) {
            TernLog.w(TAG, "Session $sessionId could not be abandoned through Dhizuku: ${e.message}")
        } catch (e: SecurityException) {
            TernLog.w(TAG, "Session $sessionId was already gone: ${e.message}")
        }
    }

    override fun liveSessionIds(): Set<Int> {
        made.keys.removeIf { !sessionExists(it) }
        return made.keys.toSet()
    }

    override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int {
        val old = made.filterValues { nowMs - it > maxAgeMs }.keys
        old.forEach(::abandon)
        return old.size
    }

    /** Session [sessionId], every call on it made by Dhizuku. */
    private fun open(sessionId: Int): PackageInstaller.Session {
        val calls = NonSdkCalls.get()
        return calls.session(calls.installer(dhizuku::wrap), sessionId, dhizuku::wrap)
    }

    /** Runs [block], and puts into words whatever stopped it: why Dhizuku could not be used, or what Android said. */
    private inline fun <T> inWords(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw told(e, ::state, words)
    }

    companion object {
        private const val TAG = "TernDhizuku"

        /**
         * What an install is told when [thrown] stopped it: the words for why Dhizuku could not be
         * used, or what Android said, as an IOException. A SecurityException stays as it is only
         * when Dhizuku, asked again with [now], still lets Tern in: then it was Android that refused.
         */
        internal fun told(thrown: RuntimeException, now: () -> DhizukuState, words: (DhizukuState) -> String): Exception = when (thrown) {
            is DhizukuException -> IOException(words(thrown.state), thrown)
            // Dhizuku turns away an app it no longer lets in as Android turns away a caller.
            is SecurityException -> now().let { state -> if (state == DhizukuState.READY) thrown else IOException(words(state), thrown) }
            // Android sends an IOException of its own inside a RuntimeException.
            else -> thrown.cause as? IOException ?: IOException(thrown.message ?: thrown.javaClass.simpleName, thrown)
        }

        /** What an install that stopped at Dhizuku says, after "The install failed:". */
        @StringRes
        fun problemWords(state: DhizukuState): Int = when (state) {
            DhizukuState.UNSUPPORTED -> R.string.installer_dhizuku_unsupported
            DhizukuState.NOT_INSTALLED -> R.string.dhizuku_failed_not_installed
            DhizukuState.NOT_OWNER -> R.string.dhizuku_failed_not_owner
            DhizukuState.NOT_ALLOWED -> R.string.dhizuku_failed_not_allowed
            // A call cut off while Dhizuku answers again counts as one it did not answer.
            DhizukuState.NOT_ANSWERING, DhizukuState.READY -> R.string.dhizuku_failed_not_answering
        }
    }
}
