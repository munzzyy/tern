package io.github.munzzyy.tern.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Process
import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.log.TernLog
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs through pm, run by [shell] as root or as the shell user. That user may install without
 * asking, so the first install of an app is silent too, and so is every update on Android 10 and
 * 11. The files are the ones the gate passed, written into the session unchanged, and Android
 * still holds an update to the signer of the installed app. The session is made with Tern named
 * as the installer, or Google Play when [installerFor] says so for the app.
 */
class ShellInstaller(
    private val shell: PrivilegedShell,
    /** The installer of record for the package: Tern's own name, or another an app is set to. */
    private val installerFor: (packageName: String) -> String,
    /** Whether Android still has the session. It does not list a session the shell made among Tern's own. */
    private val sessionExists: (sessionId: Int) -> Boolean,
    /** Where the answer of a commit goes: to [InstallReceiver], as PackageInstaller sends its own. */
    private val deliver: (appId: String, sessionId: Int, status: Int, message: String?) -> Unit,
    /** The user Tern runs as, so an install is for that user as PackageInstaller's would be. */
    private val userId: () -> Int = { Process.myUid() / PER_USER_RANGE },
) : Installer {
    /** Sessions this process made through [shell]. Android does not list them among Tern's own. */
    private val made = ConcurrentHashMap<Int, Long>()

    /** Sessions pm said it installed, by when, until their OBB files are put in place. */
    private val installed = ConcurrentHashMap<Int, Long>()

    override fun prepare(packageName: String, apks: List<File>, claimUpdateOwnership: Boolean): Int {
        require(apks.isNotEmpty())
        val create = shell.run(
            listOf(
                "pm", "install-create", "-r",
                "-i", installerFor(packageName),
                "--pkg", packageName,
                "--install-reason", INSTALL_REASON_USER,
                "--user", userId().toString(),
                "-S", apks.sumOf { it.length() }.toString(),
            ),
        )
        val sessionId = PmOutput.sessionId(create.said) ?: throw IOException(failure("install-create", create))
        made[sessionId] = System.currentTimeMillis()
        try {
            apks.forEachIndexed { i, apk ->
                val write = shell.run(listOf("pm", "install-write", "-S", apk.length().toString(), sessionId.toString(), "apk-$i.apk", "-"), apk)
                if (write.exitCode != 0 || !PmOutput.succeeded(write.said)) throw IOException(failure("install-write", write))
            }
        } catch (e: IOException) {
            abandon(sessionId)
            throw e
        }
        return sessionId
    }

    /** pm answers once Android is done, so the answer is sent on to [InstallReceiver] as PackageInstaller would send it. */
    override fun commit(appId: String, sessionId: Int) {
        val (status, message) = try {
            PmOutput.outcome(shell.run(listOf("pm", "install-commit", sessionId.toString())))
        } catch (e: IOException) {
            PackageInstaller.STATUS_FAILURE to e.message
        } finally {
            made.remove(sessionId)
        }
        if (status == PackageInstaller.STATUS_SUCCESS) installed[sessionId] = System.currentTimeMillis()
        deliver(appId, sessionId, status, message)
    }

    override val placesObb: Boolean get() = true

    /**
     * The shell may write to the OBB folder of any app, which Tern itself may not without asking
     * for storage. Each file is streamed in as pm is streamed a file, then its size is read back.
     */
    override fun placeObb(sessionId: Int, packageName: String, files: List<ObbFile>): ObbOutcome {
        if (installed.remove(sessionId) == null) return ObbOutcome.NOT_INSTALLED
        if (!BinaryManifest.isValidName(packageName)) throw IOException("$packageName is not a package name")
        val folder = ObbNames.folder(userId(), packageName)
        val mkdir = shell.run(listOf("mkdir", "-p", folder))
        if (mkdir.exitCode != 0) throw IOException(failed("mkdir", mkdir))
        for (obb in files) {
            val name = ObbNames.of(obb.name) ?: throw IOException("${obb.name} is not a name to write")
            val source = obb.file ?: throw IOException("$name was not unpacked")
            val target = "$folder/$name"
            val write = shell.run(listOf("dd", "of=$target", "bs=65536"), source)
            if (write.exitCode != 0) throw IOException(failed("dd", write))
            val size = shell.run(listOf("stat", "-c", "%s", target))
            if (size.exitCode != 0 || size.out.trim() != source.length().toString()) throw IOException("$name was not written whole")
        }
        return ObbOutcome.PLACED
    }

    override fun abandon(sessionId: Int) {
        made.remove(sessionId)
        try {
            shell.run(listOf("pm", "install-abandon", sessionId.toString()))
        } catch (e: IOException) {
            TernLog.w(TAG, "Session $sessionId could not be abandoned: ${e.message}")
        }
    }

    override fun liveSessionIds(): Set<Int> = made.keys.filterTo(HashSet(), sessionExists)

    override fun abandonOlderThan(maxAgeMs: Long, nowMs: Long): Int {
        installed.values.removeIf { nowMs - it > maxAgeMs }
        val old = made.filterValues { nowMs - it > maxAgeMs }.keys
        old.forEach(::abandon)
        return old.size
    }

    private fun failure(step: String, result: ShellResult): String = failed("pm $step", result)

    private fun failed(what: String, result: ShellResult): String =
        "$what: " + (result.said.lineSequence().firstOrNull { it.isNotBlank() }?.take(300) ?: "exit ${result.exitCode}")

    companion object {
        private const val TAG = "TernShellInstall"

        fun of(context: Context, shell: PrivilegedShell, installerFor: (String) -> String): ShellInstaller = ShellInstaller(
            shell = shell,
            installerFor = installerFor,
            sessionExists = { context.packageManager.packageInstaller.getSessionInfo(it) != null },
            deliver = { appId, sessionId, status, message ->
                context.sendBroadcast(
                    Intent(context, InstallReceiver::class.java)
                        .setAction(InstallReceiver.ACTION)
                        .setPackage(context.packageName)
                        .putExtra(InstallReceiver.EXTRA_APP_ID, appId)
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                        .putExtra(PackageInstaller.EXTRA_STATUS, status)
                        .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message),
                )
            },
        )

        /** PackageManager.INSTALL_REASON_USER, as pm takes it. */
        private const val INSTALL_REASON_USER = "4"

        /** UserHandle.PER_USER_RANGE: the part of a uid that names the user. Tern installs for the user it runs as, as PackageInstaller would. */
        const val PER_USER_RANGE = 100_000
    }
}
