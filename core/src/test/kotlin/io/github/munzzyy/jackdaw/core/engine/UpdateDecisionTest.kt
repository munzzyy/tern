package io.github.munzzyy.jackdaw.core.engine

import io.github.munzzyy.jackdaw.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val keyOld = "c".repeat(64)
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
        assertEquals(Decision.UpToDate(release("v1.2.3-fdroid")), decide(release("v1.2.3-fdroid"), installed("1.2.3", 10)))
        assertEquals(Decision.UpToDate(release("release-2024.10.01")), decide(release("release-2024.10.01"), installed("2024.10.1", 10)))
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
        assertEquals(Block.SIGNER_MISMATCH, (decide(r, null, inspection = foreign, pinned = listOf(keyA)) as Decision.Blocked).block)
        val unsigned = inspection(11, signers = emptyList())
        assertEquals(Block.SIGNER_MISMATCH, (decide(r, installed("1", 10), inspection = unsigned) as Decision.Blocked).block)
    }

    @Test
    fun firstInstallWithoutAPinIsAllowed() {
        val r = release("v2")
        assertEquals(Decision.NotInstalled(r), decide(r, null, inspection = inspection(11, signers = listOf(keyB))))
    }

    @Test
    fun aPinOutranksTheInstalledSigner() {
        val r = release("v2")
        val decision = decide(r, installed("1", 10, signers = listOf(keyB)), inspection = inspection(11, signers = listOf(keyB)), pinned = listOf(keyA))
        assertEquals(Block.SIGNER_MISMATCH, (decision as Decision.Blocked).block)
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
        assertEquals(Decision.UpdateAvailable(r, certain = true), decide(r, installed("1", 10), indexSigners = listOf(keyA)))
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
