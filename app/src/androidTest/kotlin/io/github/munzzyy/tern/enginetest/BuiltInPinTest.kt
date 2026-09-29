package io.github.munzzyy.tern.enginetest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.apk.BytesSource
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.suggest.ConfirmedBy
import io.github.munzzyy.tern.core.suggest.SuggestedApp
import io.github.munzzyy.tern.core.suggest.SuggestedKind
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.Received
import io.github.munzzyy.tern.engine.SignerState
import io.github.munzzyy.tern.engine.real.Texts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The certificate of an app of the starter list is known before its first install. The list is
 * replaced by one entry for the test forge here, because nobody but a developer can sign a file
 * with the certificate the real list carries for them.
 */
@RunWith(AndroidJUnit4::class)
class BuiltInPinTest {
    private val texts = Texts(targetContext)
    private val v1 = asset("apk/app-v1.apk")
    private val usual = signersOf(v1)
    private val other = signersOf(asset("apk/app-v2-otherkey.apk"))

    private fun signersOf(apk: ByteArray): List<String> = ApkInspector.inspect(BytesSource(apk)).signersFor(Build.VERSION.SDK_INT).map { it.sha256 }

    private fun listWith(signers: List<String>) = listOf(
        SuggestedApp(
            "Fixture", FakeForge.PROJECT, SuggestedKind.TOOLS, television = false, summaryKey = "aegis", packageName = PKG,
            fdroidId = PKG, signers = signers, confirmedBy = ConfirmedBy.FDROID,
        ),
    )

    private fun serveV1(h: Harness) {
        h.forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", v1))))
    }

    @Before
    fun setUp() {
        prepareDevice()
        uninstallFixture()
        assertEquals("the fixture has one signer", 1, usual.size)
        assertNotEquals("the two fixtures have to be signed with two keys", usual, other)
    }

    @After
    fun tearDown() {
        Prompt.dismiss()
        uninstallFixture()
    }

    @Test
    fun aFileSignedWithTheCarriedCertificateIsInstalled() = runBlocking {
        Harness("pin-same", catalog = listWith(usual)).use { h ->
            serveV1(h)
            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            assertTrue(found.builtInPin)
            assertEquals(SignerState.MATCHES_PIN, found.verification?.signerState)
            assertFalse(found.warnings.toString(), texts.warnBuiltInPin() in found.warnings)
            assertEquals(usual, h.engine.proposedConfig(found).pinnedSigners)
            assertTrue(h.engine.suggestions().single().pinned)

            val id = h.engine.add(found, install = true)
            h.confirm(id)
            waitUntil(60_000, "v1 to be installed and settled") { h.settledOn(id, 1) }
            assertEquals(usual, h.row(id).config.pinnedSigners)
            assertNull(h.describe(id), h.row(id).problem)
        }
    }

    @Test
    fun aFileSignedWithAnotherCertificateIsRefusedBeforeTheFirstInstall() = runBlocking {
        Harness("pin-other", catalog = listWith(other)).use { h ->
            serveV1(h)
            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            assertTrue(found.builtInPin)
            assertEquals(SignerState.MISMATCH, found.verification?.signerState)
            assertTrue(found.warnings.toString(), texts.warnBuiltInPin() in found.warnings)
            assertEquals(other, h.engine.proposedConfig(found).pinnedSigners)

            val id = h.engine.add(found, install = true)
            waitUntil(30_000, "the gate to decide") { h.state(id).block != null || h.state(id).installProblem != null }
            waitUntil(5_000, "the row to show the refusal") { h.row(id).progress == null }

            assertEquals(h.describe(id), ProblemKind.PIN_MISMATCH, h.state(id).block?.problem?.kind)
            assertEquals(texts.builtInPinMismatch(), h.state(id).block?.problem?.message)
            val row = h.row(id)
            assertEquals(h.describe(id), AppStatus.BLOCKED, row.status)
            assertEquals(ProblemKind.PIN_MISMATCH, row.problem?.kind)
            assertEquals(texts.builtInPinMismatch(), row.problem?.message)
            assertEquals(SignerState.MISMATCH, row.verification?.signerState)
            assertEquals(0, h.installer.prepared.get())
            assertNull(installedVersionCode())
            assertTrue(h.eventsFor(id).none { it.kind == EventKind.INSTALLED })
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.BLOCKED })
            assertFalse("the installer's dialog opened", Prompt.visible())
        }
    }

    @Test
    fun anAppThatIsNotOnTheListIsTakenOnTrustAsBefore() = runBlocking {
        val elsewhere = listWith(other).map { it.copy(url = "${FakeForge.BASE}/example/another") }
        Harness("pin-none", catalog = elsewhere).use { h ->
            serveV1(h)
            val found = h.engine.detect(FakeForge.PROJECT) as Detection.Found
            assertFalse(found.builtInPin)
            assertEquals(SignerState.FIRST_SEEN, found.verification?.signerState)
            assertEquals(emptyList<String>(), h.engine.proposedConfig(found).pinnedSigners)
            assertFalse(h.engine.suggestions().isEmpty())
        }
    }

    @Test
    fun anImportedAppOfTheListIsHeldToTheCarriedCertificate() = runBlocking {
        Harness("pin-import", catalog = listWith(other)).use { h ->
            serveV1(h)
            val carried = AppConfig(id = "imported", source = SourceSpec(SourceTypes.FORGEJO, FakeForge.PROJECT), name = "Fixture", packageName = PKG, pinnedSigners = usual)
            val export = TernExport.write(listOf(carried), 1L, "0.1.0").toByteArray()
            val summary = h.engine.importReceived(Received.ExportFile("tern-export.json", export))
            assertEquals(1, summary.added)
            assertEquals(listOf("Fixture"), summary.withPins)

            waitUntil(30_000, "the imported app to be checked") { h.row("imported").lastCheckedMs != null && !h.row("imported").checking }
            val row = h.row("imported")
            assertEquals(other, row.config.pinnedSigners)
            assertEquals(h.describe("imported"), AppStatus.BLOCKED, row.status)
            assertEquals(ProblemKind.PIN_MISMATCH, row.problem?.kind)
            assertEquals(texts.builtInPinMismatch(), row.problem?.message)
        }
    }

    @Test
    fun aPinOfTheUsersOwnStillRefusesInItsOwnWords() = runBlocking {
        Harness("pin-own").use { h ->
            serveV1(h)
            val id = h.addFixture()
            h.engine.configure(id) { it.copy(pinnedSigners = other) }
            h.engine.check(id)
            val row = h.row(id)
            assertEquals(h.describe(id), AppStatus.BLOCKED, row.status)
            assertEquals(ProblemKind.PIN_MISMATCH, row.problem?.kind)
            assertEquals(texts.pinMismatch(), row.problem?.message)
        }
    }
}
