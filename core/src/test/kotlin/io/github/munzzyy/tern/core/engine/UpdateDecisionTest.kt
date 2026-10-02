package io.github.munzzyy.tern.core.engine

import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.apk.BytesSource
import io.github.munzzyy.tern.core.apk.SigningFixtures
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val keyOld = "c".repeat(64)
    private val keyExtra = "e".repeat(64)
    private val pkg = "org.example.app"

    private fun release(tag: String, code: Long? = null) = Release(id = tag, version = tag, versionCode = code)

    private fun installed(name: String?, code: Long, signers: List<String> = listOf(keyA)) = InstalledApp(pkg, name, code, signers)

    private fun inspection(code: Long, signers: List<String> = listOf(keyA), name: String = pkg, lineage: List<String> = emptyList()) =
        Inspection(name, code, null, signers, lineage)

    private fun decide(
        release: Release,
        installed: InstalledApp?,
        record: InstallRecord? = null,
        inspection: Inspection? = null,
        expectedPackage: String? = null,
        pinned: List<String> = emptyList(),
        indexSigners: List<String> = emptyList(),
    ) = UpdateDecision.decide(release, installed, record, inspection, expectedPackage, pinned, indexSigners)

    @Test
    fun theFilesOwnVersionCodeSettlesIt() {
        val r = release("totally-unrelated-tag")
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1.0", 10), inspection = inspection(11)))
        assertEquals(Decision.UpToDate(r), decide(r, installed("1.0", 10), inspection = inspection(10)))
        assertEquals(Decision.UpToDate(r, installedIsNewer = true), decide(r, installed("1.0", 10), inspection = inspection(9)))
        assertEquals(Decision.NotInstalled(r), decide(r, null, inspection = inspection(9)))
    }

    @Test
    fun aDecoratedTagDoesNotCauseAnUpdateLoop() {
        val r = release("v1.2.3-fdroid")
        assertEquals(Decision.NeedsInspection(r), decide(r, installed("1.2.3", 10)))
        assertEquals(Decision.UpToDate(r), decide(r, installed("1.2.3", 10), inspection = inspection(10)))
        assertEquals(Decision.UpToDate(r), UpdateDecision.guess(r, installed("1.2.3", 10), null))
    }

    @Test
    fun aRebuildUnderTheSameNameIsNotMissed() {
        val r = release("v1.2.3")
        assertEquals(Decision.NeedsInspection(r), decide(r, installed("1.2.3", 10)))
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1.2.3", 10), inspection = inspection(11)))
    }

    @Test
    fun aReplacedFileUnderTheSameReleaseIsInspected() {
        val r = release("v1.2.3")
        val app = installed("1.2.3", 10)
        val record = InstallRecord("v1.2.3", "v1.2.3", 10, fileSha256 = "a".repeat(64), fileSize = 500)
        fun file(sha: String?, size: Long?) = Asset("app.apk", "https://example.org/app.apk", size = size, sha256 = sha)

        assertEquals(Decision.UpToDate(r), UpdateDecision.decide(r, app, record, null, null, emptyList(), file = file("A".repeat(64), 500)))
        assertEquals(Decision.NeedsInspection(r), UpdateDecision.decide(r, app, record, null, null, emptyList(), file = file("b".repeat(64), 500)))
        assertEquals(Decision.UpToDate(r), UpdateDecision.decide(r, app, record, null, null, emptyList(), file = file(null, 500)))
        assertEquals(Decision.NeedsInspection(r), UpdateDecision.decide(r, app, record, null, null, emptyList(), file = file(null, 501)))
        assertEquals(Decision.UpToDate(r), UpdateDecision.decide(r, app, record, null, null, emptyList(), file = file(null, null)))
    }

    @Test
    fun anInspectionGoesByTheSchemeTheDeviceUses() {
        val info = io.github.munzzyy.tern.core.apk.ApkInspector.inspect(
            io.github.munzzyy.tern.core.apk.BytesSource(io.github.munzzyy.tern.core.testing.Fixtures.bytes("apk/app-rotated.apk")),
        )
        val original = "eef5b9ce5894133be26265ab43801b2e142e846f4be056d13f0484bcedcc0e63"
        val rotated = "b09831fa62fc9415839a8d1bd7e6b32c607bf8fccd64a0893f5dba368ee62c86"
        val modern = Inspection.of(info, 36)
        assertEquals(listOf(rotated), modern.signers)
        assertEquals(listOf(original, rotated), modern.lineage)
        assertEquals(4L, modern.versionCode)
        assertEquals("com.example.app", modern.packageName)
        assertEquals(listOf(original), Inspection.of(info, 30).signers)
    }

    @Test
    fun theInstallRecordSettlesItWhenNamesNeverMatch() {
        val r = release("build-412")
        val record = InstallRecord("build-412", "build-412", 10)
        assertEquals(Decision.UpToDate(r), decide(r, installed("3.1 (internal)", 10), record))
        val updatedElsewhere = installed("3.2 (internal)", 11)
        assertEquals(Decision.NeedsInspection(r), decide(r, updatedElsewhere, record))
    }

    @Test
    fun asksForInspectionInsteadOfGuessing() {
        val r = release("v1.3.0")
        assertEquals(Decision.NeedsInspection(r), decide(r, installed("1.2.3", 10)))
        assertEquals(Decision.NeedsInspection(r), decide(r, installed(null, 10)))
    }

    @Test
    fun usesAVersionCodeFromASignedIndex() {
        assertEquals(Decision.UpdateAvailable(release("12", 12), certain = true), decide(release("12", 12), installed("1.0", 10)))
        assertEquals(Decision.UpToDate(release("10", 10)), decide(release("10", 10), installed("1.0", 10)))
    }

    @Test
    fun blocksAFileForAnotherPackage() {
        val r = release("v2")
        val other = inspection(11, name = "org.evil.app")
        assertEquals(Block.PACKAGE_MISMATCH, (decide(r, installed("1", 10), inspection = other) as Decision.Blocked).block)
        assertEquals(Block.PACKAGE_MISMATCH, (decide(r, null, inspection = other, expectedPackage = pkg) as Decision.Blocked).block)
    }

    @Test
    fun blocksAFileFromAnotherSigner() {
        val r = release("v2")
        val foreign = inspection(11, signers = listOf(keyB))
        assertEquals(Block.SIGNER_MISMATCH, (decide(r, installed("1", 10), inspection = foreign) as Decision.Blocked).block)
        assertEquals(Block.PIN_MISMATCH, (decide(r, null, inspection = foreign, pinned = listOf(keyA)) as Decision.Blocked).block)
        val unsigned = inspection(11, signers = emptyList())
        assertEquals(Block.SIGNER_MISMATCH, (decide(r, installed("1", 10), inspection = unsigned) as Decision.Blocked).block)
    }

    @Test
    fun firstInstallWithoutAPinIsAllowed() {
        val r = release("v2")
        assertEquals(Decision.NotInstalled(r), decide(r, null, inspection = inspection(11, signers = listOf(keyB))))
    }

    @Test
    fun thePinAndTheInstalledAppMustBothAgree() {
        val r = release("v2")
        fun block(installedKey: String, fileKey: String, pin: String) =
            (decide(r, installed("1", 10, signers = listOf(installedKey)), inspection = inspection(11, signers = listOf(fileKey)), pinned = listOf(pin)) as? Decision.Blocked)?.block

        assertEquals(Block.PIN_MISMATCH, block(installedKey = keyB, fileKey = keyB, pin = keyA))
        assertEquals(null, block(installedKey = keyA, fileKey = keyA, pin = keyA))
    }

    @Test
    fun aPinThatCameWithAnImportCannotExcuseAFileTheInstalledAppWouldRefuse() {
        val r = release("v2")
        val decision = decide(r, installed("1", 10, signers = listOf(keyA)), inspection = inspection(11, signers = listOf(keyB)), pinned = listOf(keyB))
        assertEquals(Block.SIGNER_MISMATCH, (decision as Decision.Blocked).block)
        val fromIndex = decide(release("12", 12), installed("1", 10, signers = listOf(keyA)), pinned = listOf(keyB), indexSigners = listOf(keyB))
        assertEquals(Block.SIGNER_MISMATCH, (fromIndex as Decision.Blocked).block)
    }

    @Test
    fun aRotatedKeyMustDescendFromWhatIsInstalledAndFromThePin() {
        val r = release("v2")
        val rotated = inspection(11, signers = listOf(keyB), lineage = listOf(keyOld, keyB))
        val onPhone = installed("1", 10, signers = listOf(keyOld))
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, onPhone, inspection = rotated, pinned = listOf(keyOld)))
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, onPhone, inspection = rotated, pinned = listOf(keyB)))
        assertEquals(Block.PIN_MISMATCH, (decide(r, onPhone, inspection = rotated, pinned = listOf(keyA)) as Decision.Blocked).block)
    }

    @Test
    fun anInstalledAppWhoseSignerCouldNotBeReadBlocksNothingByItself() {
        val r = release("v2")
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1", 10, signers = emptyList()), inspection = inspection(11, signers = listOf(keyB))))
    }

    @Test
    fun acceptsARotatedKeyThatDescendsFromTheKnownOne() {
        val r = release("v2")
        val rotated = inspection(11, signers = listOf(keyB), lineage = listOf(keyOld, keyB))
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1", 10, signers = listOf(keyOld)), inspection = rotated))
    }

    @Test
    fun pinsCompareWithoutRegardToCase() {
        val r = release("v2")
        assertEquals(Decision.NotInstalled(r), decide(r, null, inspection = inspection(11), pinned = listOf(keyA.uppercase())))
    }

    @Test
    fun blocksWhenASignedIndexNamesAnotherSigner() {
        val r = release("12", 12)
        assertEquals(Block.SIGNER_MISMATCH, (decide(r, installed("1", 10), indexSigners = listOf(keyB)) as Decision.Blocked).block)
        assertEquals(Block.PIN_MISMATCH, (decide(r, null, pinned = listOf(keyA), indexSigners = listOf(keyB)) as Decision.Blocked).block)
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1", 10), indexSigners = listOf(keyA)))
    }

    private fun signerBlockFor(file: List<String>, onPhone: List<String>?, pinned: List<String> = emptyList(), lineage: List<String> = emptyList()) =
        UpdateDecision.blockFor(Inspection(pkg, 2, "2", file, lineage), onPhone?.let { installed("1", 1, signers = it) }, pkg, pinned)?.first

    @Test
    fun anAddedSignerIsNotExcusedByThePin() {
        assertEquals(Block.PIN_MISMATCH, signerBlockFor(listOf(keyA, keyExtra), onPhone = null, pinned = listOf(keyA)))
    }

    @Test
    fun anAddedSignerDoesNotMatchTheInstalledApp() {
        assertEquals(Block.SIGNER_MISMATCH, signerBlockFor(listOf(keyA, keyExtra), onPhone = listOf(keyA)))
    }

    @Test
    fun anAppWithSeveralSignersTakesOnlyThatSameSet() {
        assertEquals(Block.SIGNER_MISMATCH, signerBlockFor(listOf(keyA), onPhone = listOf(keyA, keyExtra)))
        assertNull(signerBlockFor(listOf(keyA, keyExtra), onPhone = listOf(keyExtra, keyA), pinned = listOf(keyA, keyExtra)))
    }

    @Test
    fun aPinWithAlternativesStillTakesOneSigner() {
        assertNull(signerBlockFor(listOf(keyA), onPhone = null, pinned = listOf(keyA, keyB)))
        assertNull(signerBlockFor(listOf(keyB), onPhone = listOf(keyA), pinned = listOf(keyA), lineage = listOf(keyA, keyB)))
    }

    @Test
    fun aRealTwoSignerFileNeedsBothCertificatesPinned() {
        val name = "v2-two-signers.apk"
        val file = Inspection.of(ApkInspector.inspect(BytesSource(SigningFixtures.bytes(name))), 36)
        val both = SigningFixtures.certificatesAt(name, 29)!!.toList()
        assertEquals(2, file.signers.size)
        assertEquals(Block.PIN_MISMATCH, UpdateDecision.blockFor(file, null, file.packageName, listOf(both.first()))?.first)
        assertNull(UpdateDecision.blockFor(file, null, file.packageName, both))
    }

    @Test
    fun aSignedIndexNamingAnAddedSignerIsBlocked() {
        val r = release("12", 12)
        assertEquals(Block.PIN_MISMATCH, (decide(r, null, pinned = listOf(keyA), indexSigners = listOf(keyA, keyExtra)) as Decision.Blocked).block)
    }

    @Test
    fun guessingIsMarkedAsAGuess() {
        val app = installed("1.2.3", 10)
        assertEquals(Decision.UpdateAvailable(release("v1.3.0"), certain = false), UpdateDecision.guess(release("v1.3.0"), app, null))
        assertEquals(Decision.UpToDate(release("v1.2.3")), UpdateDecision.guess(release("v1.2.3"), app, null))
        assertEquals(Decision.UpToDate(release("v1.0.0"), installedIsNewer = true), UpdateDecision.guess(release("v1.0.0"), app, null))
        assertEquals(Decision.UpdateAvailable(release("latest"), certain = false), UpdateDecision.guess(release("latest"), app, null))
        val record = InstallRecord("v1.2.3", "v1.2.3", 10)
        assertEquals(Decision.UpdateAvailable(release("v1.4"), certain = false), UpdateDecision.guess(release("v1.4"), installed("custom", 10), record))
    }

    @Test
    fun noBlockWithoutAnythingToCompareAgainst() {
        assertNull(UpdateDecision.blockFor(inspection(1, signers = listOf(keyB)), null, null, emptyList()))
        assertTrue(UpdateDecision.blockFor(inspection(1, signers = listOf(keyB)), null, null, listOf(keyA)) != null)
    }
}
