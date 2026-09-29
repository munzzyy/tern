package io.github.munzzyy.jackdaw.core.verify

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumsTest {
    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)
    private val sha1 = "a".repeat(40)
    private val md5 = "a".repeat(32)

    @Test
    fun gnuStyleTwoSpaces() {
        assertEquals(hexA, Checksums.parse("$hexA  app-release.apk")["app-release.apk"])
    }

    @Test
    fun gnuStyleOneSpace() {
        assertEquals(hexA, Checksums.parse("$hexA app.apk")["app.apk"])
    }

    @Test
    fun gnuStyleBinaryMarker() {
        assertEquals(hexA, Checksums.parse("$hexA *app.apk")["app.apk"])
    }

    @Test
    fun gnuStyleMultipleLines() {
        val text = "$hexA  a.apk\n$hexB  b.apk\n"
        val parsed = Checksums.parse(text)
        assertEquals(hexA, parsed["a.apk"])
        assertEquals(hexB, parsed["b.apk"])
    }

    @Test
    fun bsdStyle() {
        assertEquals(hexA, Checksums.parse("SHA256 (app.apk) = $hexA")["app.apk"])
    }

    @Test
    fun bsdStyleLowercaseTag() {
        assertEquals(hexA, Checksums.parse("sha256 (app.apk) = $hexA")["app.apk"])
    }

    @Test
    fun bsdStyleFilenameWithSpaces() {
        assertEquals(hexA, Checksums.parse("SHA256 (release notes.apk) = $hexA")["release notes.apk"])
    }

    @Test
    fun bareHexLine() {
        assertEquals(hexA, Checksums.parse(hexA)[""])
    }

    @Test
    fun bareHexLineWithSurroundingWhitespace() {
        assertEquals(hexA, Checksums.parse("  $hexA  \n")[""])
    }

    @Test
    fun ignoresSha1() {
        assertTrue(Checksums.parse("$sha1  app.apk").isEmpty())
    }

    @Test
    fun ignoresMd5() {
        assertTrue(Checksums.parse("$md5  app.apk").isEmpty())
    }

    @Test
    fun markdownTableRow() {
        val text = "| app.apk | `$hexA` |"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun markdownHexOnNextLine() {
        val text = "app.apk\n$hexA\n"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun markdownHexOnPreviousLine() {
        val text = "$hexA\napp.apk\n"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun markdownListItem() {
        val text = "- app.apk: `$hexA`"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun markdownCodeSpanBothSides() {
        val text = "`app.apk` = `$hexA`"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun multipleHexOnOneLineWithFilenamesEach() {
        val text = "a.apk $hexA and b.apk $hexB"
        val parsed = Checksums.parse(text)
        assertEquals(hexA, parsed["a.apk"])
        assertEquals(hexB, parsed["b.apk"])
    }

    @Test
    fun doesNotMatchHexEmbeddedInLongerHexRun() {
        val longer = hexA + "0"
        assertTrue(Checksums.parse("$longer app.apk").isEmpty())
    }

    @Test
    fun doesNotMatchHexPrecededByHexDigit() {
        val longer = "f" + hexA
        assertTrue(Checksums.parse("$longer app.apk").isEmpty())
    }

    @Test
    fun ignoresPlainProseWithoutHex() {
        assertTrue(Checksums.parse("This release fixes a crash on startup.").isEmpty())
    }

    @Test
    fun ignoresVersionNumbersThatArentHex() {
        assertTrue(Checksums.parse("Version 1.2.3 released today.").isEmpty())
    }

    @Test
    fun capsAtOneMegabyte() {
        val huge = "x".repeat(2 * 1024 * 1024)
        Checksums.parse(huge)
    }

    @Test
    fun capsAtFiveThousandLines() {
        val text = (1..6000).joinToString("\n") { "line $it" }
        Checksums.parse(text)
    }

    @Test
    fun blankLinesAreIgnored() {
        val text = "\n\n$hexA  app.apk\n\n\n"
        assertEquals(hexA, Checksums.parse(text)["app.apk"])
    }

    @Test
    fun caseInsensitiveHexIsLowercased() {
        val upper = hexA.uppercase()
        assertEquals(hexA, Checksums.parse("$upper  app.apk")["app.apk"])
    }

    @Test
    fun gnuLineIgnoresTrailingWhitespace() {
        assertEquals(hexA, Checksums.parse("$hexA  app.apk   \n")["app.apk"])
    }

    @Test
    fun markdownDoesNotMatchStrayHexOnlyNeighborLine() {
        val text = "$hexA\n$hexB\n"
        assertTrue(Checksums.parse(text)[""] != null)
    }

    @Test
    fun expectedForPrefersInlineSha256() {
        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk", sha256 = hexA)
        val release = Release(id = "1", version = "1.0", assets = listOf(asset))
        assertEquals(hexA, Checksums.expectedFor(release, asset) { error("should not fetch") })
    }

    @Test
    fun expectedForFallsBackToSiblingChecksumAsset() {
        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk")
        val sums = Asset(name = "app.apk.sha256", url = "https://example.com/app.apk.sha256", kind = AssetKind.CHECKSUM)
        val release = Release(id = "1", version = "1.0", assets = listOf(asset, sums))
        val result = Checksums.expectedFor(release, asset) { "$hexA  app.apk" }
        assertEquals(hexA, result)
    }

    @Test
    fun expectedForFallsBackToSharedSumsFile() {
        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk")
        val sums = Asset(name = "SHA256SUMS", url = "https://example.com/SHA256SUMS", kind = AssetKind.CHECKSUM)
        val release = Release(id = "1", version = "1.0", assets = listOf(asset, sums))
        val result = Checksums.expectedFor(release, asset) { "$hexA  app.apk\n$hexB  other.apk" }
        assertEquals(hexA, result)
    }

    @Test
    fun expectedForFallsBackToReleaseNotes() {
        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk")
        val release = Release(id = "1", version = "1.0", notes = "app.apk\n$hexA", assets = listOf(asset))
        assertEquals(hexA, Checksums.expectedFor(release, asset) { error("should not fetch") })
    }

    @Test
    fun expectedForReturnsNullWhenNothingMatches() {
        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk")
        val release = Release(id = "1", version = "1.0", notes = "no hash here", assets = listOf(asset))
        assertNull(Checksums.expectedFor(release, asset) { "" })
    }
}
