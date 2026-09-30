package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.engine.Block
import io.github.munzzyy.tern.core.engine.Decision
import io.github.munzzyy.tern.core.engine.Inspection
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.engine.UpdateDecision
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.suggest.Catalog
import io.github.munzzyy.tern.core.suggest.ConfirmedBy
import io.github.munzzyy.tern.core.suggest.SuggestedApp
import io.github.munzzyy.tern.core.suggest.SuggestedKind
import io.github.munzzyy.tern.engine.real.Arrivals
import io.github.munzzyy.tern.engine.real.BuiltInPins
import io.github.munzzyy.tern.engine.real.Suggestions
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
        val held = pins.orElse(SourceSpec("forgejo", url), "org.example.app", carriedWithALink)
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
        assertEquals(listOf(developer, later), pins.orElse(SourceSpec("forgejo", address), "org.example.app", listOf(stranger)))
    }

    @Test
    fun anAddressTheListDoesNotCarryACertificateForIsHeldToNothingNew() {
        for (url in listOf(plain.url, "https://forge.example.org/example/other", "https://forge.example.org/example/app-fork", "")) {
            assertEquals(url, emptyList<String>(), pins.of(url))
            assertTrue(url, firstInstall(url, stranger) is Decision.NotInstalled)
            assertEquals(url, listOf(stranger), pins.orElse(SourceSpec("forgejo", url), "org.example.app", listOf(stranger)))
        }
    }

    private val store = SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/example/org.example.app")

    private fun firstInstallFrom(spec: SourceSpec, signer: String, packageName: String? = "org.example.app"): Decision =
        UpdateDecision.decide(
            release, installed = null, record = null, inspection = fileSignedBy(signer), expectedPackage = packageName,
            pinnedSigners = pins.orElse(spec, packageName, emptyList()),
        )

    @Test
    fun aCarriedAppAddedFromAStoreIsHeldToTheDevelopersCertificateByItsPackage() {
        assertEquals(listOf(developer, later), pins.forApp(store, "org.example.app"))
        assertEquals(Block.PIN_MISMATCH, (firstInstallFrom(store, stranger) as Decision.Blocked).block)
        assertTrue(firstInstallFrom(store, developer) is Decision.NotInstalled)
        for (type in SourceTypes.THIRD_PARTY_STORES + listOf(SourceTypes.HTML, SourceTypes.DIRECT)) {
            val elsewhere = SourceSpec(type, "https://mirror.example.net/org.example.app")
            assertEquals(type, Block.PIN_MISMATCH, (firstInstallFrom(elsewhere, stranger) as Decision.Blocked).block)
        }
        assertTrue(pins.hold(AppConfig(id = "s", source = store, name = "Example", packageName = "org.example.app", pinnedSigners = listOf(developer))))
    }

    @Test
    fun aPackageFromSeveralEntriesIsHeldToAllOfTheirCertificates() {
        val other = carried.copy(name = "Example Nightly", url = "https://forge.example.org/example/nightly", signers = listOf(stranger.uppercase()))
        val both = BuiltInPins(listOf(carried, other, plain))
        assertEquals(listOf(developer, later, stranger), both.ofPackage("org.example.app"))
        assertEquals(emptyList<String>(), both.ofPackage("org.example.unknown"))
        assertEquals(emptyList<String>(), both.ofPackage(null))
    }

    @Test
    fun aForkOnAForgeAndAnFDroidRepositoryAreNotHeldByPackage() {
        for (type in listOf(SourceTypes.GITHUB, SourceTypes.GITLAB, SourceTypes.FORGEJO, SourceTypes.FDROID, SourceTypes.FDROID_REPO, SourceTypes.ITCHIO)) {
            val spec = SourceSpec(type, "https://elsewhere.example.org/fork/app")
            assertEquals(type, emptyList<String>(), pins.forApp(spec, "org.example.app"))
            assertTrue(type, firstInstallFrom(spec, stranger) is Decision.NotInstalled)
        }
        assertEquals("an app of an unknown package is held to nothing new", emptyList<String>(), pins.forApp(store, "org.example.other"))
    }

    @Test
    fun anImportedAppFromAStoreIsStoredWithTheCarriedCertificate() {
        val imported = AppConfig(id = "x", source = store, name = "Example", packageName = "org.example.app")
        assertEquals(listOf(developer, later), Arrivals.stored(imported, "x", pins).pinnedSigners)
        val unknown = imported.copy(packageName = null)
        assertEquals(emptyList<String>(), Arrivals.stored(unknown, "x", pins).pinnedSigners)
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
    fun aRefusalSaysThatTernCarriesTheCertificateOnlyWhenItDoes() {
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
