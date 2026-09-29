package io.github.munzzyy.stamp.core.source

import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.AssetKind
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownListingTest {
    private val turn = "‮"
    private val hidden = "​"

    private class Fixed(private val result: CheckResult) : Source {
        override val type = "fixed"

        override fun match(url: String): SourceSpec? = null

        override fun check(spec: SourceSpec, context: CheckContext): CheckResult = result
    }

    private fun checked(listing: SourceListing): SourceListing {
        val registry = SourceRegistry(listOf(Fixed(CheckResult.Listing(listing))))
        val result = registry.check(SourceSpec("fixed", "https://example.org/app"), CheckContext(FakeHttp(), InMemoryValidatorStore()))
        return (result as CheckResult.Listing).listing
    }

    private val hostile = SourceListing(
        releases = listOf(
            Release(
                id = "v1.0${turn}0.2",
                version = " 1.0${turn}0.2\n",
                title = "First$hidden  release\r\nof many",
                notes = "Notes$turn stay\nas they came",
                pageUrl = "https://example.org/app/releases/1",
                assets = listOf(
                    Asset(name = "app-${turn}kpa.exe", url = "https://example.org/app-$turn.apk", sha256 = "a".repeat(64), kind = AssetKind.APK),
                ),
            ),
        ),
        name = "Bank${turn}knaB$hidden  of\n\tNames",
        author = "⁦Some  Body⁩",
        packageName = "org.example.app",
        description = "One line and another\u0007",
        movedTo = "https://example.org/new${turn}home",
        learnedOptions = mapOf("fingerprint" to "abc"),
    )

    @Test
    fun whatAPersonReadsIsFitToBeShown() {
        val listing = checked(hostile)
        assertEquals("BankknaB of Names", listing.name)
        assertEquals("Some Body", listing.author)
        assertEquals("One line and another", listing.description)
        assertEquals("https://example.org/newhome", listing.movedTo)
        val release = listing.releases.single()
        assertEquals("1.00.2", release.version)
        assertEquals("First release of many", release.title)
        assertEquals("app-kpa.exe", release.assets.single().name)
    }

    @Test
    fun whatIdentifiesAndLocatesStaysAsItCame() {
        val listing = checked(hostile)
        val release = listing.releases.single()
        assertEquals("v1.0${turn}0.2", release.id)
        assertEquals("Notes$turn stay\nas they came", release.notes)
        assertEquals("https://example.org/app/releases/1", release.pageUrl)
        val asset = release.assets.single()
        assertEquals("https://example.org/app-$turn.apk", asset.url)
        assertEquals("a".repeat(64), asset.sha256)
        assertEquals(AssetKind.APK, asset.kind)
        assertEquals("org.example.app", listing.packageName)
        assertEquals(mapOf("fingerprint" to "abc"), listing.learnedOptions)
    }

    @Test
    fun aTextWithNothingToShowIsAbsent() {
        val listing = checked(SourceListing(releases = emptyList(), name = "$turn$hidden", author = " \n ", description = hidden, movedTo = turn))
        assertNull(listing.name)
        assertNull(listing.author)
        assertNull(listing.description)
        assertNull(listing.movedTo)
    }

    @Test
    fun textsAreCutToTheirLengths() {
        val long = "x".repeat(5000)
        val listing = checked(
            SourceListing(
                releases = listOf(Release(id = long, version = long, title = long, assets = listOf(Asset(name = "$long.apk", url = "https://example.org/a.apk")))),
                name = long,
                author = long,
                description = long,
            ),
        )
        assertEquals(200, listing.name?.length)
        assertEquals(200, listing.author?.length)
        assertEquals(1000, listing.description?.length)
        val release = listing.releases.single()
        assertEquals(100, release.version.length)
        assertEquals(300, release.title?.length)
        assertEquals(200, release.assets.single().name.length)
        assertEquals(AssetKind.APK, release.assets.single().kind)
        assertEquals(5000, release.id.length)
    }

    @Test
    fun anOrdinaryListingComesThroughUnchanged() {
        val ordinary = SourceListing(
            releases = listOf(
                Release(id = "v2.1.0", version = "2.1.0", title = "Version 2.1.0", notes = "- fixed", assets = listOf(Asset("app-2.1.0.apk", "https://example.org/app-2.1.0.apk"))),
            ),
            name = "Example App",
            author = "example",
            description = "An app that serves as an example.",
        )
        assertEquals(ordinary, checked(ordinary))
    }

    @Test
    fun anUnchangedAnswerStaysUnchanged() {
        val registry = SourceRegistry(listOf(Fixed(CheckResult.Unchanged)))
        val result = registry.check(SourceSpec("fixed", "https://example.org/app"), CheckContext(FakeHttp(), InMemoryValidatorStore()))
        assertTrue(result is CheckResult.Unchanged)
    }
}
