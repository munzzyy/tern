package io.github.munzzyy.tern.log

import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.engine.EventKind

/**
 * Hands Tern's own messages to [keep], the activity log, while [on] says to keep them, each
 * written by [entry] first. An exception on the way stays here: a message that cannot be kept is
 * still in Android's log, and keeping it must not stop what was being done. A message that comes
 * up while one is handed over is not kept, so a log that cannot be written does not go on
 * writing about it.
 */
class Journal(private val on: () -> Boolean, private val keep: (EventKind, String) -> Unit) {
    private val handing = ThreadLocal<Boolean>()

    fun take(kind: EventKind, message: String, error: Throwable? = null) {
        if (handing.get() == true) return
        handing.set(true)
        try {
            if (on()) keep(kind, entry(message, error))
        } catch (_: RuntimeException) {
        } finally {
            handing.set(false)
        }
    }

    companion object {
        /** The most frames of Tern's own code an error is kept with. */
        const val MAX_FRAMES = 5

        /** The longest entry, which is what the store keeps of a message too. */
        const val MAX_CHARS = 2000

        private const val MAX_LINE = 600

        /** How much of a message is read at all; one that is longer is cut before its last word. */
        private const val MAX_READ = 64 * 1024

        private const val OWN_CODE = "io.github.munzzyy.tern."

        /** Code that is not Tern's: Android, Java, Kotlin and the libraries Tern is built with. */
        private val OTHERS = listOf(
            "android.", "androidx.", "com.android.", "dalvik.", "libcore.", "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.", "org.jetbrains.", "org.json.", "org.xml.", "org.w3c.", "org.apache.", "org.junit.", "junit.",
            "com.google.", "rikka.", "dev.rikka.", "moe.shizuku.",
        )

        /**
         * What the log keeps of [message] and [error]: the message, then the error's class and its
         * message, then at most [MAX_FRAMES] frames of Tern's own code, the innermost first. Never
         * the whole stack, and nothing [Scrub] takes out.
         */
        fun entry(message: String, error: Throwable?): String {
            val text = StringBuilder(safe(message))
            if (error != null) {
                text.append('\n').append(error.javaClass.name)
                safe(error.message.orEmpty()).takeIf { it.isNotEmpty() }?.let { text.append(": ").append(it) }
                for (frame in ownFrames(error.stackTrace)) text.append("\nat ").append(frame)
            }
            return Shown.prose(text.toString(), MAX_CHARS)
        }

        /**
         * The first frames of [trace] that are Tern's own. A release build renames most of Tern's
         * classes, so a frame is taken as Tern's own when its class is under Tern's name or under
         * none that is known to be someone else's.
         */
        fun ownFrames(trace: Array<StackTraceElement>, max: Int = MAX_FRAMES): List<String> =
            trace.asSequence()
                .filter { frame -> frame.className.startsWith(OWN_CODE) || OTHERS.none { frame.className.startsWith(it) } }
                .take(max)
                .map { "${it.className}.${it.methodName}(${where(it)})" }
                .toList()

        private fun where(frame: StackTraceElement): String {
            val file = frame.fileName
            return when {
                frame.isNativeMethod -> "Native Method"
                file == null -> "Unknown Source"
                frame.lineNumber >= 0 -> "$file:${frame.lineNumber}"
                else -> file
            }
        }

        /** [text] on one line, with [Scrub] done before it is cut, so no cut ever leaves half a token behind. */
        private fun safe(text: String): String {
            val read = if (text.length <= MAX_READ) text else text.take(MAX_READ).let { it.take(it.indexOfLast(Char::isWhitespace).coerceAtLeast(0)) }
            return Shown.line(Scrub.text(Shown.line(read, MAX_READ)), MAX_LINE)
        }
    }
}
