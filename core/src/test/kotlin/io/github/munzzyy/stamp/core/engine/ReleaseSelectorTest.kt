package io.github.munzzyy.stamp.core.engine

import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.text.PatternException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseSelectorTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day
    private val apk = listOf(Asset("app.apk", "https://example.org/app.apk"))

    private fun release(tag: String, daysAgo: Int = 30, pre: Boolean = false, title: String? = null, notes: String? = null, assets: List<Asset> = apk) =
        Release(id = tag, version = tag, title = title, notes = notes, publishedAtMs = now - daysAgo * day, prerelease = pre, assets = assets)

    private fun pick(releases: List<Release>, policy: ReleasePolicy = ReleasePolicy()) =
        ReleaseSelector.select(releases, policy, now) { it.installable.isNotEmpty() }

    @Test
    fun highestVersionBeatsMostRecentDate() {
        val picked = pick(listOf(release("v1.9.5", daysAgo = 1), release("v2.0.1", daysAgo = 20), release("v2.0.0", daysAgo = 40)))
        assertEquals("v2.0.1", picked.candidate!!.id)
    }

    @Test
    fun aTagWithoutAVersionComesAfterTheVersionedOnes() {
        val mixed = listOf(release("continuous", daysAgo = 1), release("v1.9.5", daysAgo = 5), release("v2.0.1", daysAgo = 20))
        assertEquals("v2.0.1", pick(mixed).candidate!!.id)
        assertEquals("continuous", pick(mixed, ReleasePolicy(tagFilter = "^continuous$")).candidate!!.id)
        val rolling = listOf(release("continuous", daysAgo = 1), release("weekly", daysAgo = 3))
        assertEquals("continuous", pick(rolling).candidate!!.id)
    }

    @Test
    fun leavesOutPrereleasesUnlessAsked() {
        val releases = listOf(release("v3.0.0-beta.1"), release("v2.1.0", pre = true), release("v2.0.0"))
        assertEquals("v2.0.0", pick(releases).candidate!!.id)
        assertEquals(listOf(Rejection.PRERELEASE, Rejection.PRERELEASE), pick(releases).rejected.map { it.second })
        assertEquals("v3.0.0-beta.1", pick(releases, ReleasePolicy(includePrereleases = true)).candidate!!.id)
    }

    @Test
    fun appliesFilters() {
        val releases = listOf(
            release("desktop-v5.0", title = "Desktop 5.0"),
            release("android-v4.2", title = "Android 4.2", notes = "fixes"),
            release("android-v4.1", title = "Android 4.1", notes = "security fixes"),
        )
        assertEquals("android-v4.2", pick(releases, ReleasePolicy(tagFilter = "^android-")).candidate!!.id)
        assertEquals("android-v4.2", pick(releases, ReleasePolicy(titleFilter = "android")).candidate!!.id)
        assertEquals("android-v4.1", pick(releases, ReleasePolicy(notesFilter = "security")).candidate!!.id)
    }

    @Test
    fun extractsTheVersionBeforeComparing() {
        val releases = listOf(release("build-77-v1.2.0"), release("build-80-v1.10.0"))
        val picked = pick(releases, ReleasePolicy(versionExtract = "v(\\d+\\.\\d+\\.\\d+)"))
        val candidate = picked.candidate!!
        assertEquals("1.10.0", candidate.version)
        assertEquals("build-80-v1.10.0", candidate.id)
    }

    @Test
    fun holdsBackReleasesYoungerThanTheMinimumAge() {
        val releases = listOf(release("v2.0.0", daysAgo = 2), release("v1.9.0", daysAgo = 9))
        val picked = pick(releases, ReleasePolicy(minAgeDays = 7))
        assertEquals("v1.9.0", picked.candidate!!.id)
        assertEquals(Rejection.TOO_NEW, picked.rejected.single().second)
        val undated = listOf(release("v2.0.0").copy(publishedAtMs = null))
        assertEquals("v2.0.0", pick(undated, ReleasePolicy(minAgeDays = 7)).candidate!!.id)
    }

    @Test
    fun skipsTheReleaseTheUserSkipped() {
        val releases = listOf(release("v2.0.0"), release("v1.9.0"))
        assertEquals("v1.9.0", pick(releases, ReleasePolicy(skippedReleaseId = "v2.0.0")).candidate!!.id)
    }

    @Test
    fun fallsBackToAnOlderReleaseWithAFile() {
        val releases = listOf(release("v2.0.0", assets = emptyList()), release("v1.9.0"))
        assertEquals("v1.9.0", pick(releases).candidate!!.id)
        val strict = pick(releases, ReleasePolicy(fallbackToOlder = false))
        assertNull(strict.candidate)
        assertEquals(Rejection.NO_USABLE_FILE, strict.rejected.single().second)
    }

    @Test
    fun reportsABrokenPattern() {
        assertThrows(PatternException::class.java) { pick(listOf(release("v1")), ReleasePolicy(tagFilter = "(unclosed")) }
        assertThrows(PatternException::class.java) { pick(listOf(release("v1")), ReleasePolicy(tagFilter = "a".repeat(501))) }
    }

    @Test
    fun emptyInputGivesNoCandidate() {
        assertNull(pick(emptyList()).candidate)
    }

    @Test
    fun aReleaseThatOnlyOffersAnotherAppsFileIsPassedOverForTheNextOne() {
        val releases = listOf(release("v2.0.0"), release("v1.9.0"))
        val wrongPackage = setOf("v2.0.0")
        val picked = ReleaseSelector.select(releases, ReleasePolicy(), now, matchesPackage = { it.id !in wrongPackage }) { it.installable.isNotEmpty() }
        assertEquals("v1.9.0", picked.candidate!!.id)
        assertEquals(Rejection.WRONG_PACKAGE, picked.rejected.single().second)
    }

    @Test
    fun givesUpAfterFourReleasesOfTheWrongPackageAndTakesTheFifthAsIs() {
        val releases = (5 downTo 1).map { release("v$it.0.0") }
        var checked = 0
        val picked = ReleaseSelector.select(releases, ReleasePolicy(), now, matchesPackage = { checked++; false }) { it.installable.isNotEmpty() }
        assertEquals("v1.0.0", picked.candidate!!.id)
        assertEquals(4, checked)
        assertEquals(4, picked.rejected.count { it.second == Rejection.WRONG_PACKAGE })
    }

    @Test
    fun everyInspectedReleaseBeingTheWrongPackageIsReportedAsSuch() {
        val releases = listOf(release("v2.0.0"), release("v1.9.0"))
        val picked = ReleaseSelector.select(releases, ReleasePolicy(), now, matchesPackage = { false }) { it.installable.isNotEmpty() }
        assertNull(picked.candidate)
        assertEquals(listOf(Rejection.WRONG_PACKAGE, Rejection.WRONG_PACKAGE), picked.rejected.map { it.second })
    }

    @Test
    fun withoutAPackageCheckEveryUsableReleaseIsAccepted() {
        val releases = listOf(release("v2.0.0"), release("v1.9.0"))
        val picked = ReleaseSelector.select(releases, ReleasePolicy(), now) { it.installable.isNotEmpty() }
        assertEquals("v2.0.0", picked.candidate!!.id)
    }
}
