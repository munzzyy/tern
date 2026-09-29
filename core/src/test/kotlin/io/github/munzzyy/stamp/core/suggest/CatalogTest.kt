package io.github.munzzyy.stamp.core.suggest

import io.github.munzzyy.stamp.core.apk.BinaryManifest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.SourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

class CatalogTest {
    private val summaries: Map<String, String> by lazy { readSummaries() }

    private fun projectFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(checkNotNull(dir) { "not inside the project" }, path)
    }

    private fun readSummaries(): Map<String, String> {
        val file = projectFile("app/src/main/res/values/strings_suggest.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace(Regex("""\\(.)"""), "$1")
        }
    }

    private fun summary(app: SuggestedApp): String? = summaries["suggest_${app.summaryKey}"]

    @Test
    fun everyAddressIsHttpsAndInTheFormDetectionSettlesOn() {
        val registry = SourceRegistry.standard()
        for (app in Catalog.all) {
            assertTrue("${app.name}: ${app.url} is not HTTPS", Urls.isHttps(app.url))
            assertFalse("${app.name}: ${app.url} names no host", URI(app.url).host.isNullOrEmpty())
            assertEquals("${app.name}: not in its plain form", app.url, Urls.normalize(app.url))
            assertEquals("${app.name}: detection would store another address", app.url, registry.match(app.url)?.url ?: app.url)
        }
    }

    @Test
    fun noTwoEntriesShareAnAddressANameOrAFile() {
        fun repeated(values: List<String>) = values.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), repeated(Catalog.all.map { it.name }))
        assertEquals(emptySet<String>(), repeated(Catalog.all.map { it.url.trimEnd('/') }))
        assertEquals(emptySet<String>(), repeated(Catalog.all.map { it.packageName }))
        assertEquals(emptySet<String>(), repeated(Catalog.all.map { it.summaryKey }))
    }

    @Test
    fun everyEntryHasASummaryOfAtMostFiftyCharacters() {
        for (app in Catalog.all) {
            val text = summary(app)
            assertNotNull("${app.name}: no string named suggest_${app.summaryKey}", text)
            val length = text!!.codePointCount(0, text.length)
            assertTrue("${app.name}: the summary is empty", text.isNotBlank())
            assertTrue("${app.name}: the summary has $length characters: $text", length <= 50)
            assertEquals("${app.name}: the summary has space around it", text.trim(), text)
        }
    }

    @Test
    fun noSummaryIsLeftWithoutItsEntry() {
        val used = Catalog.all.map { "suggest_${it.summaryKey}" }.toSet()
        assertEquals(emptySet<String>(), summaries.keys - used)
    }

    @Test
    fun everyEntrySaysWhyItMayBeListed() {
        for (app in Catalog.all) {
            val grounds = listOfNotNull(app.fdroidId, app.publisher?.takeIf { it.isNotBlank() }, "own".takeIf { app.own })
            assertTrue("${app.name}: neither carried by F-Droid, nor by a named organisation, nor the author's own", grounds.isNotEmpty())
            assertTrue("${app.name}: ${app.packageName} is not an application id", BinaryManifest.isValidName(app.packageName))
            app.fdroidId?.let { assertTrue("${app.name}: $it is not an application id", BinaryManifest.isValidName(it)) }
        }
    }

    @Test
    fun theAuthorsOwnAppsSaySoAndNoOtherDoes() {
        for (app in Catalog.all) {
            val marked = summary(app).orEmpty().endsWith(", by Stamp's author")
            assertEquals("${app.name}: ${summary(app)}", app.own, marked)
            if (app.own) assertTrue("${app.name}: ${app.url}", app.url.startsWith("https://github.com/munzzyy/"))
        }
    }

    @Test
    fun clientsThatStripAdvertisingAndClosedAppsStayOff() {
        val refused = listOf("smarttube", "tizentube", "s0undtv", "stremio")
        for (app in Catalog.all) {
            val text = "${app.name} ${app.url} ${app.packageName}".lowercase().filter { it.isLetterOrDigit() }
            assertEquals("${app.name} is on the list", emptyList<String>(), refused.filter { it in text })
        }
    }

    @Test
    fun everyFingerprintIsSixtyFourLowercaseHexCharacters() {
        val shape = Regex("^[0-9a-f]{64}$")
        for (app in Catalog.all) {
            for (fingerprint in app.signers) assertTrue("${app.name}: $fingerprint", shape.matches(fingerprint))
            assertEquals("${app.name} names a fingerprint twice", app.signers.distinct(), app.signers)
        }
    }

    @Test
    fun noTwoAppsShareAFingerprint() {
        val holders = Catalog.all.flatMap { app -> app.signers.map { it to app.name } }.groupBy({ it.first }, { it.second })
        assertEquals(emptyMap<String, List<String>>(), holders.filterValues { it.size > 1 })
    }

    @Test
    fun aFingerprintIsOnlyCarriedWithTheSecondPlaceThatConfirmedIt() {
        for (app in Catalog.all) {
            assertEquals("${app.name}: ${app.signers.size} fingerprints, confirmed by ${app.confirmedBy}", app.signers.isNotEmpty(), app.confirmedBy != null)
        }
        assertTrue(Catalog.all.any { it.signers.isNotEmpty() })
        assertTrue(Catalog.all.any { it.signers.isEmpty() })
    }

    @Test
    fun theDocumentNamesEveryFingerprintAndNoOther() {
        val text = projectFile("docs/SUGGESTIONS.md").readText()
        val carried = Catalog.all.flatMap { it.signers }.toSet()
        for (app in Catalog.all) {
            for (fingerprint in app.signers) {
                val line = text.lineSequence().firstOrNull { it.contains(fingerprint) }
                assertNotNull("${app.name}: docs/SUGGESTIONS.md does not name $fingerprint", line)
                assertTrue("${app.name}: $fingerprint stands in the line of another app: $line", line!!.startsWith("| ${app.name} |"))
            }
        }
        val pinned = text.substringAfter("## The certificates the list carries").substringBefore("\n## ")
        val named = Regex("`([0-9a-f]{64})`").findAll(pinned).map { it.groupValues[1] }.toSet()
        assertEquals("the document names as carried what the list does not carry", emptySet<String>(), named - carried)
    }

    @Test
    fun anEntryIsFoundByItsAddressInAnyCase() {
        val aegis = Catalog.all.first { it.name == "Aegis Authenticator" }
        assertEquals(aegis, Catalog.entryAt("https://github.com/beemdevelopment/Aegis"))
        assertEquals(aegis, Catalog.entryAt("https://github.com/BeemDevelopment/aegis"))
        assertEquals(aegis, Catalog.entryAt(" https://github.com/beemdevelopment/Aegis/ "))
        assertEquals("OsmAnd", Catalog.entryAt("https://download.osmand.net/releases")?.name)
        assertEquals(null, Catalog.entryAt("https://github.com/beemdevelopment/Aegis-fork"))
        assertEquals(null, Catalog.entryAt("https://github.com/beemdevelopment"))
        assertEquals(null, Catalog.entryAt("https://example.org/github.com/beemdevelopment/Aegis"))
        assertEquals(null, Catalog.entryAt(""))
        for (app in Catalog.all) assertEquals(app, Catalog.entryAt(app.url))
    }

    @Test
    fun theListStaysShort() {
        assertTrue("${Catalog.all.size} entries", Catalog.all.size in 20..28)
        assertTrue("${Catalog.all.count { it.television }} for a television", Catalog.all.count { it.television } in 6..10)
    }

    private fun entry(name: String, kind: SuggestedKind, television: Boolean = false, own: Boolean = false) = SuggestedApp(
        name, "https://example.org/$name", kind, television, name.lowercase(), "org.example.${name.lowercase()}", fdroidId = "org.example", own = own,
    )

    private val sample = listOf(
        entry("Zebra", SuggestedKind.TOOLS),
        entry("aardvark", SuggestedKind.TOOLS, own = true),
        entry("Heron", SuggestedKind.MEDIA),
        entry("Badger", SuggestedKind.GAMES, television = true),
        entry("Otter", SuggestedKind.TOOLS, television = true),
        entry("Finch", SuggestedKind.TOOLS, television = true, own = true),
        entry("crane", SuggestedKind.MEDIA),
    )

    @Test
    fun theOrderIsByKindThenByNameWithTheAuthorsOwnLastInTheirKind() {
        val names = Catalog.ordered(television = false, apps = sample).map { it.name }
        assertEquals(listOf("crane", "Heron", "Otter", "Zebra", "aardvark", "Finch", "Badger"), names)
    }

    @Test
    fun onATelevisionItsOwnEntriesComeFirst() {
        val names = Catalog.ordered(television = true, apps = sample).map { it.name }
        assertEquals(listOf("Otter", "Finch", "Badger", "crane", "Heron", "Zebra", "aardvark"), names)
    }

    @Test
    fun theCatalogComesOutWholeAndInThatOrder() {
        for (television in listOf(false, true)) {
            val ordered = Catalog.ordered(television)
            assertEquals(Catalog.all.size, ordered.size)
            assertEquals(Catalog.all.toSet(), ordered.toSet())
            assertEquals(Catalog.ordered(television, Catalog.all.reversed()), ordered)
        }
        val count = Catalog.all.count { it.television }
        assertTrue(Catalog.ordered(television = true).take(count).all { it.television })
        assertFalse(Catalog.ordered(television = false).take(count).all { it.television })
    }
}
