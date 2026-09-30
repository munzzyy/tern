package io.github.munzzyy.tern.log

import io.github.munzzyy.tern.engine.EventKind
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the log keeps of one of Tern's own messages, and when it keeps it at all. */
class JournalTest {
    private fun frame(className: String, method: String = "run", file: String? = "${className.substringAfterLast('.')}.kt", line: Int = 12) =
        StackTraceElement(className, method, file, line)

    private fun failure(message: String?, vararg frames: StackTraceElement) = IOException(message).apply { stackTrace = arrayOf(*frames) }

    @Test
    fun aMessageIsKeptWithNothingSecretInIt() {
        assertEquals(
            "Could not fetch https://example.org/app",
            Journal.entry("Could not fetch https://user:pass@example.org/app?token=abc123def456", null),
        )
        assertEquals("Refused …", Journal.entry("Refused ghp_0123456789abcdefghijklmnopqrstuvwxyz", null))
    }

    @Test
    fun anErrorIsKeptAsItsClassItsMessageAndFiveFramesOfTernsOwnCode() {
        val ours = (1..8).map { frame("io.github.munzzyy.tern.engine.real.Checks", "step$it", line = it) }
        val error = failure(
            "HTTP 403 for https://objects.example.org/asset?X-Amz-Signature=0123456789abcdef",
            frame("java.net.SocketInputStream", "read", "SocketInputStream.java"),
            frame("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt"),
            *ours.toTypedArray(),
            frame("android.os.Handler", "dispatchMessage", "Handler.java"),
        )
        val lines = Journal.entry("Install of a1 stopped", error).lines()
        assertEquals("Install of a1 stopped", lines[0])
        assertEquals("java.io.IOException: HTTP 403 for https://objects.example.org/asset", lines[1])
        assertEquals((1..5).map { "at io.github.munzzyy.tern.engine.real.Checks.step$it(Checks.kt:$it)" }, lines.drop(2))
        assertEquals(Journal.MAX_FRAMES + 2, lines.size)
    }

    @Test
    fun anErrorWithoutAMessageIsKeptAsItsClass() {
        val lines = Journal.entry("Engine task failed", failure(null, frame("io.github.munzzyy.tern.App", "start"))).lines()
        assertEquals(listOf("Engine task failed", "java.io.IOException", "at io.github.munzzyy.tern.App.start(App.kt:12)"), lines)
    }

    @Test
    fun noFrameOfAndroidJavaKotlinOrALibraryIsKept() {
        val others = listOf(
            "android.os.Looper", "androidx.compose.runtime.Recomposer", "com.android.internal.os.ZygoteInit", "dalvik.system.VMStack",
            "java.lang.Thread", "javax.net.ssl.SSLSocket", "kotlin.coroutines.jvm.internal.BaseContinuationImpl", "kotlinx.coroutines.DispatchedTask",
            "com.google.common.Foo", "rikka.shizuku.Shizuku", "org.json.JSONObject",
        )
        assertEquals(emptyList<String>(), Journal.ownFrames(others.map { frame(it) }.toTypedArray()))
    }

    @Test
    fun aRenamedClassOfAReleaseBuildCountsAsTernsOwn() {
        val trace = arrayOf(frame("java.io.FileInputStream", "read"), frame("o.a", "b", "SourceFile", 4), frame("io.github.munzzyy.tern.work.CheckJobService", "onStartJob"))
        assertEquals(listOf("o.a.b(SourceFile:4)", "io.github.munzzyy.tern.work.CheckJobService.onStartJob(CheckJobService.kt:12)"), Journal.ownFrames(trace))
    }

    @Test
    fun aFrameSaysWhereItIsAsWellAsItCan() {
        val trace = arrayOf(
            StackTraceElement("io.github.munzzyy.tern.A", "native", "A.kt", -2),
            StackTraceElement("io.github.munzzyy.tern.B", "unknown", null, -1),
            StackTraceElement("io.github.munzzyy.tern.C", "noLine", "C.kt", -1),
        )
        assertEquals(listOf("io.github.munzzyy.tern.A.native(Native Method)", "io.github.munzzyy.tern.B.unknown(Unknown Source)", "io.github.munzzyy.tern.C.noLine(C.kt)"), Journal.ownFrames(trace))
    }

    @Test
    fun theWholeStackIsNeverKept() {
        val deep = (1..500).map { frame("io.github.munzzyy.tern.Deep", "call$it", line = it) }.toTypedArray()
        val text = Journal.entry("Engine task failed", failure("boom", *deep))
        assertEquals(Journal.MAX_FRAMES, text.lines().count { it.startsWith("at ") })
        assertFalse(text.contains("call6("))
    }

    @Test
    fun anEntryIsOneLineOfMessageAndBounded() {
        val text = Journal.entry("first\nsecond\r\n\tthird\u0007 and ‮turned", null)
        assertEquals("first second third and turned", text)
        val long = Journal.entry("word ".repeat(10_000), failure("detail ".repeat(10_000), *Array(9) { frame("io.github.munzzyy.tern.X${"y".repeat(300)}") }))
        assertTrue(long.length <= Journal.MAX_CHARS)
        assertTrue(long.lines().first().length <= 600)
    }

    @Test
    fun aSecretCutByTheLengthReadIsLeftOutWhole() {
        // The query that is taken out brings what follows it to the front of what is kept. Only five
        // characters of the code would be read, too few to look like a secret on their own.
        val before = "https://example.org/?" + "q".repeat(65_503) + " token="
        val message = before + "a1b2c3d4e5f6a7b8c9d0"
        assertEquals(64 * 1024 - 5, before.length)
        val kept = Journal.entry(message, null)
        assertFalse(kept, kept.contains("a1b2"))
        assertEquals("https://example.org/", kept)
    }

    @Test
    fun nothingIsKeptWhileTheSettingIsOff() {
        val kept = ArrayList<Pair<EventKind, String>>()
        var on = false
        val journal = Journal({ on }) { kind, text -> kept += kind to text }
        journal.take(EventKind.OWN_WARNING, "Before")
        assertTrue(kept.isEmpty())
        on = true
        journal.take(EventKind.OWN_WARNING, "After, with key=0123456789abcdef")
        journal.take(EventKind.OWN_ERROR, "Broke", failure("disk full"))
        assertEquals(listOf(EventKind.OWN_WARNING to "After, with key=…", EventKind.OWN_ERROR to "Broke\njava.io.IOException: disk full"), kept)
    }

    @Test
    fun whatTheLogSaysWhileItKeepsSomethingIsNotKept() {
        val kept = ArrayList<String>()
        lateinit var journal: Journal
        journal = Journal({ true }) { _, text ->
            journal.take(EventKind.OWN_ERROR, "The store failed")
            kept += text
        }
        journal.take(EventKind.OWN_WARNING, "Something")
        assertEquals(listOf("Something"), kept)
        journal.take(EventKind.OWN_WARNING, "Something else")
        assertEquals(listOf("Something", "Something else"), kept)
    }

    @Test
    fun aLogThatCannotBeWrittenStopsNothing() {
        val journal = Journal({ true }) { _, _ -> throw IllegalStateException("closed") }
        journal.take(EventKind.OWN_ERROR, "Something")
        val asking = Journal({ throw IllegalStateException("no settings") }) { _, _ -> }
        asking.take(EventKind.OWN_ERROR, "Something")
    }

    @Test
    fun nothingButTheFacadeWritesToAndroidsLog() {
        val facade = File("src/main/kotlin/io/github/munzzyy/tern/log/TernLog.kt")
        assertTrue("run from the app module", facade.isFile)
        val sources = listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin").map(::File).filter { it.isDirectory }
        val writers = sources.flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.canonicalFile != facade.canonicalFile && it.readText().contains("android.util.Log") }
        assertEquals(emptyList<File>(), writers)
    }
}
