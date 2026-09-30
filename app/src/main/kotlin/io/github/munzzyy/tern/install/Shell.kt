package io.github.munzzyy.tern.install

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a command printed and how it ended. */
data class ShellResult(val exitCode: Int, val out: String, val err: String) {
    /** Both streams, for a message: pm writes its failures to either. */
    val said: String get() = listOf(out, err).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * A way to run a command as a user that may install apps without asking: root through su, or the
 * shell user through Shizuku. Neither opens a connection to anything but this device.
 */
interface PrivilegedShell {
    /** Whether the shell can be used now, without asking the person anything. */
    fun ready(): Boolean

    /**
     * Runs [command] as one program with its arguments, [input] streamed to its standard input.
     * Throws IOException when the shell cannot be reached at all.
     */
    fun run(command: List<String>, input: File? = null): ShellResult
}

/** Runs commands through su. Each call starts su afresh, so a revoked grant is noticed at once. */
class RootShell(private val su: String = "su") : PrivilegedShell {
    override fun ready(): Boolean = try {
        val result = run(listOf("id", "-u"))
        result.exitCode == 0 && result.out.trim() == "0"
    } catch (_: IOException) {
        false
    }

    override fun run(command: List<String>, input: File?): ShellResult {
        val process = ProcessBuilder(su, "-c", command.joinToString(" ", transform = ::quote)).start()
        return drive(process.outputStream, process.inputStream, process.errorStream, input, { process.waitFor(TIMEOUT_S, TimeUnit.SECONDS) }) {
            if (process.isAlive) {
                process.destroy()
                throw IOException("${command.firstOrNull()} did not finish in $TIMEOUT_S seconds")
            }
            process.exitValue()
        }
    }

    companion object {
        /** Every argument goes to su as one word, whatever it holds. */
        fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
    }
}

/**
 * Runs commands as the user Shizuku or Sui runs as, through their binder. Asking for the grant is
 * [Shizuku.requestPermission]; this only uses one that exists.
 */
class ShizukuShell : PrivilegedShell {
    override fun ready(): Boolean = state() == ShizukuState.READY

    override fun run(command: List<String>, input: File?): ShellResult {
        if (state() != ShizukuState.READY) throw IOException("Shizuku is not running, or Tern may not use it")
        val remote = try {
            IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(command.toTypedArray(), null, null)
        } catch (e: RemoteException) {
            throw IOException("Shizuku did not start ${command.firstOrNull()}", e)
        } catch (e: SecurityException) {
            throw IOException("Shizuku refused to start ${command.firstOrNull()}", e)
        } ?: throw IOException("Shizuku did not start ${command.firstOrNull()}")
        try {
            return drive(
                ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream),
                ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream),
                ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream),
                input,
                { remote.waitForTimeout(TIMEOUT_S, TimeUnit.SECONDS.name) },
            ) {
                if (remote.alive()) {
                    remote.destroy()
                    throw IOException("${command.firstOrNull()} did not finish in $TIMEOUT_S seconds")
                }
                remote.exitValue()
            }
        } catch (e: RemoteException) {
            // An answer lost with the binder counts as a failure, never as a success.
            throw IOException("Shizuku stopped while ${command.firstOrNull()} ran", e)
        }
    }

    companion object {
        fun state(): ShizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuState.TOO_OLD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                else -> ShizukuState.NOT_ALLOWED
            }
        } catch (_: RuntimeException) {
            ShizukuState.NOT_RUNNING
        }
    }
}

/** How Shizuku stands with Tern. */
enum class ShizukuState {
    /** Not installed, or installed and not started. */
    NOT_RUNNING,

    /** A version from before the binder Tern speaks. */
    TOO_OLD,

    /** Running, and Tern has not been allowed to use it. */
    NOT_ALLOWED,
    READY,
}

private const val TIMEOUT_S = 180L

/**
 * Feeds [input] to a process and reads both of its streams at once, so that a process that fills
 * one pipe while Tern waits on the other cannot stall. [wait] returns when the process ended or
 * its time is up; [exitCode] then gives the code, or ends the process and throws.
 */
private fun drive(
    stdin: OutputStream,
    stdout: InputStream,
    stderr: InputStream,
    input: File?,
    wait: () -> Unit,
    exitCode: () -> Int,
): ShellResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val readers = listOf(stdout to out, stderr to err).map { (from, to) ->
        thread(name = "tern-shell-read") {
            try {
                from.use { copyCapped(it, to) }
            } catch (_: IOException) {
            }
        }
    }
    try {
        stdin.use { sink -> input?.let { file -> FileInputStream(file).use { it.copyTo(sink, 64 * 1024) } } }
    } catch (e: IOException) {
        // A process that exits before it read everything closes the pipe; its own answer says why.
        if (input == null) throw e
    }
    wait()
    // exitCode ends a process that is still running and says so, which also ends both readers.
    val code = exitCode()
    readers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
    return ShellResult(code, out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()))
}

private fun copyCapped(from: InputStream, to: ByteArrayOutputStream) {
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = from.read(buffer)
        if (n < 0) return
        if (to.size() < MAX_OUTPUT) to.write(buffer, 0, minOf(n, MAX_OUTPUT - to.size()))
    }
}

private const val MAX_OUTPUT = 64 * 1024

/** What pm prints, read into what PackageInstaller would have said. */
object PmOutput {
    private val SESSION = Regex("""\[(\d+)]""")
    private val FAILURE = Regex("""Failure \[([A-Z0-9_]+)(?::\s*([^\]]*))?]""")

    /** The id in "Success: created install session [1234]", or null. */
    fun sessionId(text: String): Int? {
        if (!text.contains("Success")) return null
        return SESSION.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun succeeded(text: String): Boolean = text.lines().any { it.trim() == "Success" } || text.trim().startsWith("Success")

    /** The status and message a commit ended with, as PackageInstaller reports them to an app. */
    fun outcome(result: ShellResult): Pair<Int, String?> {
        val said = result.said
        if (result.exitCode == 0 && succeeded(said)) return PackageInstaller.STATUS_SUCCESS to null
        val failure = FAILURE.find(said)
        val code = failure?.groupValues?.get(1)
        val message = failure?.let { f -> listOfNotNull(code, f.groupValues[2].takeIf { it.isNotBlank() }).joinToString(": ") }
            ?: said.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return statusOf(code) to message?.take(300)
    }

    /** The same grouping Android makes for an app that installs through PackageInstaller. */
    fun statusOf(code: String?): Int = when {
        code == null -> PackageInstaller.STATUS_FAILURE
        code == "INSTALL_FAILED_ABORTED" -> PackageInstaller.STATUS_FAILURE_ABORTED
        code in BLOCKED -> PackageInstaller.STATUS_FAILURE_BLOCKED
        code in CONFLICT -> PackageInstaller.STATUS_FAILURE_CONFLICT
        code in INCOMPATIBLE -> PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
        code == "INSTALL_FAILED_INSUFFICIENT_STORAGE" || code == "INSTALL_FAILED_MEDIA_UNAVAILABLE" -> PackageInstaller.STATUS_FAILURE_STORAGE
        code == "INSTALL_FAILED_VERIFICATION_TIMEOUT" -> PackageInstaller.STATUS_FAILURE_TIMEOUT
        code.startsWith("INSTALL_PARSE_FAILED") || code in INVALID -> PackageInstaller.STATUS_FAILURE_INVALID
        else -> PackageInstaller.STATUS_FAILURE
    }

    private val BLOCKED = setOf(
        "INSTALL_FAILED_USER_RESTRICTED", "INSTALL_FAILED_VERIFICATION_FAILURE", "INSTALL_FAILED_PACKAGE_CHANGED",
        "INSTALL_FAILED_SESSION_INVALID",
    )
    private val CONFLICT = setOf(
        "INSTALL_FAILED_ALREADY_EXISTS", "INSTALL_FAILED_DUPLICATE_PACKAGE", "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", "INSTALL_FAILED_REPLACE_COULDNT_DELETE", "INSTALL_FAILED_CONFLICTING_PROVIDER",
        "INSTALL_FAILED_DUPLICATE_PERMISSION", "INSTALL_FAILED_VERSION_DOWNGRADE", "INSTALL_FAILED_WRONG_INSTALLED_VERSION",
    )
    private val INCOMPATIBLE = setOf(
        "INSTALL_FAILED_OLDER_SDK", "INSTALL_FAILED_NEWER_SDK", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE",
        "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_MISSING_SHARED_LIBRARY", "INSTALL_FAILED_MISSING_FEATURE",
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION", "INSTALL_FAILED_TEST_ONLY",
    )
    private val INVALID = setOf(
        "INSTALL_FAILED_INVALID_APK", "INSTALL_FAILED_INVALID_URI", "INSTALL_FAILED_BAD_DEX_METADATA",
        "INSTALL_FAILED_BAD_SIGNATURE", "INSTALL_FAILED_BAD_PERMISSION_GROUP", "INSTALL_FAILED_CONTAINER_ERROR",
    )
}
