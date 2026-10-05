package io.github.munzzyy.tern.core.verify

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.Release
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
        assertEquals(mapOf("" to hexA), Checksums.parse("$hexA\n$hexA\n"))
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

    @Test
    fun twoDifferentSumsWithoutANameAreNoSum() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertEquals(emptyMap<String, String>(), Checksums.parse("$b\n$a\n"))

        val asset = Asset(name = "app.apk", url = "https://example.com/app.apk")
        val sums = Asset(name = "app.apk.sha256", url = "https://example.com/app.apk.sha256", kind = AssetKind.CHECKSUM)
        val release = Release(id = "1", version = "1.0", assets = listOf(asset, sums))
        assertNull(Checksums.expectedFor(release, asset) { "$b\n$a\n" })
    }

    @Test
    fun theSameSumTwiceIsStillThatSum() {
        val a = "a".repeat(64)
        assertEquals(mapOf("" to a), Checksums.parse("$a\n$a\n"))
        assertEquals(mapOf("app.apk" to a), Checksums.parse("$a  app.apk\nSHA256 (app.apk) = $a\n"))
    }

    @Test
    fun aFileGivenTwoDifferentSumsHasNone() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        assertEquals(mapOf("other.apk" to c), Checksums.parse("$a  app.apk\n$c  other.apk\n$b  app.apk\n"))
    }

    private val app = Asset(name = "app.apk", url = "https://example.com/app.apk")

    private fun sums(name: String) = Asset(name = name, url = "https://example.com/$name")

    private fun releaseWith(vararg names: String, notes: String? = null) = Release(id = "1", version = "1.0", notes = notes, assets = listOf(app) + names.map(::sums))

    @Test
    fun aSumIsFoundInEveryPlaceItIsCommonlyPut() {
        for (name in listOf("app.apk.sha256.txt", "app.sha256")) {
            assertEquals(name, hexA, Checksums.expectedFor(releaseWith(name), app) { hexA })
        }
        for (name in listOf("SHA256SUMS-arm64.txt", "checksums_sha256.txt", "app-1.0-checksums.txt")) {
            assertEquals(name, hexA, Checksums.expectedFor(releaseWith(name), app) { "$hexA  app.apk\n$hexB  other.apk" })
        }
    }

    @Test
    fun aFileForSeveralFilesHasToNameThisOne() {
        assertNull(Checksums.expectedFor(releaseWith("SHA256SUMS-arm64.txt"), app) { hexA })
        val release = releaseWith("SHA256SUMS-arm64.txt", "SHA256SUMS-all.txt")
        val read = ArrayList<String>()
        val found = Checksums.expected(release, app) { asset ->
            read += asset.name
            if (asset.name == "SHA256SUMS-arm64.txt") "$hexB  app-arm64.apk" else "$hexA  dist/app.apk"
        }
        assertEquals(ExpectedSum(hexA, release.assets.last()), found)
        assertEquals(listOf("SHA256SUMS-arm64.txt", "SHA256SUMS-all.txt"), read)
    }

    @Test
    fun noMoreThanThreeChecksumFilesAreRead() {
        val release = releaseWith("checksums-a.txt", "checksums-b.txt", "checksums-c.txt", "checksums-d.txt")
        var read = 0
        assertNull(Checksums.expectedFor(release, app) { read++; "$hexB  other.apk" })
        assertEquals(3, read)
    }

    @Test
    fun aFileMadeForThisOneIsReadFirst() {
        val release = releaseWith("SHA256SUMS", "checksums-extra.txt", "app.apk.sha256")
        assertEquals(listOf("app.apk.sha256", "SHA256SUMS", "checksums-extra.txt"), Checksums.candidatesFor(release, app).map { it.name })
        val read = ArrayList<String>()
        assertEquals(hexA, Checksums.expectedFor(release, app) { read += it.name; hexA })
        assertEquals(listOf("app.apk.sha256"), read)
    }

    @Test
    fun aBareSumUnderANameAnotherFileSharesIsNotTaken() {
        val release = releaseWith("app.sha256").let { it.copy(assets = it.assets + Asset("app.apks", "https://example.com/app.apks")) }
        assertNull(Checksums.expectedFor(release, app) { hexA })
        assertEquals(hexA, Checksums.expectedFor(release, app) { "$hexA  app.apk" })
    }

    @Test
    fun sumsOfAnotherDigestAreNotRead() {
        assertEquals(emptyList<Asset>(), Checksums.candidatesFor(releaseWith("checksums-sha512.txt", "app.apk.md5", "checksums-sha1.txt"), app))
    }

    @Test
    fun aSumFromTheNotesIsCreditedToThemEvenAfterAFileWasRead() {
        val release = releaseWith("SHA256SUMS", notes = "app.apk\n$hexA")
        assertEquals(ExpectedSum(hexA, null), Checksums.expected(release, app) { "$hexB  other.apk" })
    }
}
