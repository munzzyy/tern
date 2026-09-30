package io.github.munzzyy.tern.data

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.munzzyy.tern.BuildConfig
import io.github.munzzyy.tern.log.Scrub
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * What Tern was doing when it stopped unexpectedly. It is written to a file of Tern's own as the
 * process ends, shown at the next start, and sent nowhere unless the person shares it.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"
    const val MAX_CHARS = 16_000

    /** Writes the report of any error nothing else caught, then lets Android end the process as it would have. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(text(thread.name, error, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, System.currentTimeMillis()))
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of [error]: where it happened and the stack, scrubbed and cut to [MAX_CHARS]. */
    fun text(thread: String, error: Throwable, version: String, sdk: Int, atMs: Long): String = scrubbed(
        buildString {
            appendLine("Tern $version on Android API $sdk")
            appendLine("${Instant.ofEpochMilli(atMs)}, thread $thread")
            append(error.stackTraceToString())
        }.take(MAX_CHARS),
    )

    /** [report] with what could open an account taken out, a line at a time so the frames stay as they were. */
    fun scrubbed(report: String): String = report.lineSequence().joinToString("\n") { Scrub.text(it) }

    /** The report of the last unexpected stop, until it is dismissed. One an older Tern wrote is scrubbed here. */
    fun pending(context: Context): String? = try {
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.take(MAX_CHARS)?.let(::scrubbed)
    } catch (_: IOException) {
        null
    }

    fun dismiss(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
