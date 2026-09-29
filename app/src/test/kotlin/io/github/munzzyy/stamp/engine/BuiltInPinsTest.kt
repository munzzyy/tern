package io.github.munzzyy.stamp.engine

import io.github.munzzyy.stamp.core.engine.Block
import io.github.munzzyy.stamp.core.engine.Decision
import io.github.munzzyy.stamp.core.engine.Inspection
import io.github.munzzyy.stamp.core.engine.InstalledApp
import io.github.munzzyy.stamp.core.engine.UpdateDecision
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.suggest.Catalog
import io.github.munzzyy.stamp.core.suggest.ConfirmedBy
import io.github.munzzyy.stamp.core.suggest.SuggestedApp
import io.github.munzzyy.stamp.core.suggest.SuggestedKind
import io.github.munzzyy.stamp.engine.real.BuiltInPins
import io.github.munzzyy.stamp.engine.real.Suggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPinsTest {
    private val developer = "a".repeat(64)
    private val later = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private val address = "https://forge.example.org/example/app"

    private val carried = SuggestedApp(
        "Example", address, SuggestedKind.TOOLS, television = false, summaryKey = "example", packageName = "org.example.app",
        fdroidId = "org.example.app", signers = listOf(developer, later), confirmedBy = ConfirmedBy.FDROID,
    )
    private val plain = carried.copy(name = "Plain", url = "https://forge.example.org/example/plain", signers = emptyList(), confirmedBy = null)
    private val pins = BuiltInPins(listOf(carried, plain))

    private val release = Release(id = "v1", version = "1.0")

    private fun config(url: String, pinned: List<String>) =
        AppConfig(id = "a", source = SourceSpec("forgejo", url), name = "Example", packageName = "org.example.app", pinnedSigners = pinned)

    private fun fileSignedBy(signer: String, lineage: List<String> = emptyList()) = Inspection("org.example.app", 1, "1.0", listOf(signer), lineage)

    private fun firstInstall(url: String, signer: String, lineage: List<String> = emptyList(), carriedWithALink: List<String> = emptyList()): Decision {
        val held = pins.orElse(url, carriedWithALink)
        return UpdateDecision.decide(release, installed = null, record = null, inspection = fileSignedBy(signer, lineage), expectedPackage = "org.example.app", pinnedSigners = held)
    }

    @Test
    fun anEntryWithACertificateHoldsItsFirstFileToIt() {
        assertEquals(listOf(developer, later), pins.of(address))
        assertTrue(firstInstall(address, developer) is Decision.NotInstalled)
        assertTrue(firstInstall(address, later) is Decision.NotInstalled)
    }

    @Test
    fun aFirstFileSignedByAnyoneElseIsRefused() {
        val decision = firstInstall(address, stranger)
        assertEquals(Block.PIN_MISMATCH, (decision as Decision.Blocked).block)
    }

    @Test
    fun aKeyThatProvesItsDescentFromTheCarriedOnePasses() {
        assertTrue(firstInstall(address, stranger, lineage = listOf(developer)) is Decision.NotInstalled)
        assertEquals(Block.PIN_MISMATCH, (firstInstall(address, stranger, lineage = listOf("d".repeat(64))) as Decision.Blocked).block)
    }

    @Test
    fun whatCameWithALinkCannotTakeTheCarriedCertificatesPlace() {
        val decision = firstInstall(address, stranger, carriedWithALink = listOf(stranger))
        assertEquals(Block.PIN_MISMATCH, (decision as Decision.Blocked).block)
        assertEquals(listOf(developer, later), pins.orElse(address, listOf(stranger)))
    }

    @Test
    fun anAddressTheListDoesNotCarryACertificateForIsHeldToNothingNew() {
        for (url in listOf(plain.url, "https://forge.example.org/example/other", "https://forge.example.org/example/app-fork", "")) {
            assertEquals(url, emptyList<String>(), pins.of(url))
            assertTrue(url, firstInstall(url, stranger) is Decision.NotInstalled)
            assertEquals(url, listOf(stranger), pins.orElse(url, listOf(stranger)))
        }
    }

    @Test
    fun theAddressIsFoundInAnyCaseAndWithATrailingSlash() {
        assertEquals(listOf(developer, later), pins.of("https://forge.example.org/Example/APP"))
        assertEquals(listOf(developer, later), pins.of("$address/"))
    }

    @Test
    fun theInstalledAppStillHasItsSay() {
        val installed = InstalledApp("org.example.app", "0.9", 0, listOf(stranger))
        val decision = UpdateDecision.decide(release, installed, null, fileSignedBy(developer), "org.example.app", pins.of(address))
        assertEquals(Block.SIGNER_MISMATCH, (decision as Decision.Blocked).block)
    }

    @Test
    fun aRefusalSaysThatStampCarriesTheCertificateOnlyWhenItDoes() {
        assertTrue(pins.hold(config(address, listOf(developer, later))))
        assertTrue(pins.hold(config(address, listOf(developer))))
        assertTrue(pins.hold(config(address, listOf(developer.uppercase()))))
        assertFalse(pins.hold(config(address, listOf(stranger))))
        assertFalse(pins.hold(config(address, listOf(developer, stranger))))
        assertFalse(pins.hold(config(address, emptyList())))
        assertFalse(pins.hold(config(plain.url, listOf(developer))))
        assertFalse(pins.hold(config("https://forge.example.org/example/other", listOf(developer))))
    }

    @Test
    fun aSuggestionSaysWhetherItsCertificateIsCarried() {
        val offered = Suggestions.list(television = false, catalog = listOf(carried.copy(summaryKey = "aegis"), plain.copy(summaryKey = "tusky"))) { "text" }
        assertEquals(mapOf("Example" to true, "Plain" to false), offered.associate { it.name to it.pinned })
    }

    @Test
    fun theListThatShipsMarksExactlyTheEntriesWithACertificate() {
        val offered = Suggestions.list(television = false) { "text" }
        assertEquals(Catalog.all.filter { it.signers.isNotEmpty() }.map { it.name }.toSet(), offered.filter { it.pinned }.map { it.name }.toSet())
        assertTrue(offered.any { it.pinned })
        assertTrue(offered.any { !it.pinned })
        val shipped = BuiltInPins()
        for (app in Catalog.all) assertEquals(app.name, app.signers, shipped.of(app.url))
    }
}
