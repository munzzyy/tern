package io.github.munzzyy.stamp.enginetest

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.EventKind
import io.github.munzzyy.stamp.engine.ProblemKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A part of a bundle that was changed after it was signed still names the right certificate, and
 * Android does not read every part by itself. The gate verifies such a part itself and refuses the
 * bundle before a session exists; nothing may be installed and the row has to say so.
 */
@RunWith(AndroidJUnit4::class)
class SplitTamperTest {
    @Before
    fun setUp() {
        prepareDevice()
        uninstallFixture()
    }

    @After
    fun tearDown() {
        prepareDevice()
        uninstallFixture()
    }

    /** The bundle of the fixture with one bit changed in every part that carries native code. The base is left as it was signed. */
    private fun tampered(): ByteArray {
        val out = ByteArrayOutputStream()
        var changed = 0
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(asset("apk/bundle.xapk"))).use { bundle ->
                while (true) {
                    val entry = bundle.nextEntry ?: break
                    val bytes = bundle.readBytes()
                    if (entry.name.startsWith("config.") && ABI_PARTS.any { it in entry.name }) {
                        bytes[LOCAL_HEADER_TIME] = (bytes[LOCAL_HEADER_TIME].toInt() xor 1).toByte()
                        changed++
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        assertEquals("the fixture has a part for each of three processors", 3, changed)
        return out.toByteArray()
    }

    @Test
    fun aPartOfABundleChangedAfterSigningIsNeverInstalled() = runBlocking {
        assumeTrue("no part of the fixture runs on ${Build.SUPPORTED_ABIS.toList()}", Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "x86_64", "armeabi-v7a") })
        Harness("split-tamper").use { h ->
            h.forge.releases = listOf(FakeForge.Release("v3.0", listOf(FakeForge.File("app-3.0.xapk", tampered()))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(60_000, "the gate or Android to refuse the bundle") {
                val state = h.state(id)
                state.pending == null && (state.block != null || state.installProblem != null)
            }
            val state = h.state(id)
            Log.i("EngineTest", "tampered part: refused by ${if (state.block != null) "the gate" else "Android"}: ${state.block?.problem ?: state.installProblem}; ${h.describe(id)}")

            assertNotNull("the gate let the bundle through and left the refusal to Android: ${state.installProblem}", state.block)
            assertEquals(ProblemKind.SIGNER_MISMATCH, state.block?.problem?.kind)
            assertEquals("a session was prepared", 0, h.installer.prepared.get())
            assertEquals("a session was committed", 0, h.installer.committed.get())
            assertTrue("the log says the file was verified", h.eventsFor(id).none { it.kind == EventKind.VERIFIED || it.kind == EventKind.INSTALLED })
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.BLOCKED })

            assertNull("the app was installed", installedVersionCode())
            assertNull("an install was recorded", state.record)
            waitUntil(5_000, "the row to show the block") { h.row(id).progress == null }
            val row = h.row(id)
            assertEquals(ProblemKind.SIGNER_MISMATCH, row.problem?.kind)
            assertEquals(null, row.installed)
            assertEquals(AppStatus.BLOCKED, row.status)
        }
    }

    @Test
    fun theSameBundleAsItWasSignedInstalls() = runBlocking {
        assumeTrue("no part of the fixture runs on ${Build.SUPPORTED_ABIS.toList()}", Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "x86_64", "armeabi-v7a") })
        Harness("split-pristine").use { h ->
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                ZipInputStream(ByteArrayInputStream(asset("apk/bundle.xapk"))).use { bundle ->
                    while (true) {
                        val entry = bundle.nextEntry ?: break
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(bundle.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            h.forge.releases = listOf(FakeForge.Release("v3.0", listOf(FakeForge.File("app-3.0.xapk", out.toByteArray()))))
            val id = h.addFixture()
            h.engine.check(id)
            h.engine.install(id)
            waitUntil(30_000, "the session to be committed") { h.installer.committed.get() == 1 || h.state(id).block != null }
            assertNull(h.state(id).block?.problem?.message, h.state(id).block)
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.VERIFIED })
            h.confirm(id)
            waitUntil(60_000, "the bundle to be installed") { installedVersionCode() == 3L && h.state(id).pending == null }
        }
    }

    private companion object {
        val ABI_PARTS = listOf("arm64_v8a", "x86_64", "armeabi_v7a")

        /** The time of the first entry in its local header: no parser reads it, and every signature scheme from v2 on covers it. */
        const val LOCAL_HEADER_TIME = 10
    }
}
