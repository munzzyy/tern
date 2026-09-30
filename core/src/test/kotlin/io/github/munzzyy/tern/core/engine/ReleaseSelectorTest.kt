package io.github.munzzyy.tern.core.engine

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.text.PatternException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

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

    /** Every release in the order the selector tried them, found by letting none of them be usable. */
    private fun tried(releases: List<Release>, policy: ReleasePolicy) = ReleaseSelector.select(releases, policy, now) { false }.rejected.map { it.first.id }

    @Test
    fun aMatchGroupChoosesWhichPartOfTheMatchBecomesTheVersion() {
        val releases = listOf(release("build-80-v1.10.0"))
        val extract = ReleasePolicy(versionExtract = "build-(\\d+)-v(\\d+)\\.(\\d+)\\.(\\d+)")
        fun version(group: String?) = pick(releases, extract.copy(matchGroup = group)).candidate!!.version
        assertEquals("80", version(null))
        assertEquals("1", version("2"))
        assertEquals("1", version("$2"))
        assertEquals("build-80-v1.10.0", version("0"))
        assertEquals("1.10.0+80", version("$2.$3.$4+$1"))
        assertEquals("$80", version("\\$$1"))
    }

    @Test
    fun aMatchGroupThePatternLacksCountsAsNoMatch() {
        val releases = listOf(release("build-80-v1.10.0"))
        val policy = ReleasePolicy(versionExtract = "v(\\d+)\\.(\\d+)")
        assertEquals("build-80-v1.10.0", pick(releases, policy.copy(matchGroup = "$3")).candidate!!.version)
        assertEquals("build-80-v1.10.0", pick(releases, policy.copy(matchGroup = "$1.$3")).candidate!!.version)
        assertEquals("build-80-v1.10.0", pick(releases, policy.copy(matchGroup = "version")).candidate!!.version)
        assertEquals("1.10", pick(releases, policy.copy(matchGroup = "$1.$2")).candidate!!.version)
    }

    @Test
    fun theTitleCanBeTheVersionWhereThereIsOne() {
        val releases = listOf(release("v81", title = "Example 2.0.0"), release("v90", title = "Example 1.9.0"))
        val picked = pick(releases, ReleasePolicy(versionFrom = VersionFrom.TITLE)).candidate!!
        assertEquals("v81", picked.id)
        assertEquals("Example 2.0.0", picked.version)
        assertEquals("v90", pick(releases).candidate!!.id)
        val untitled = pick(listOf(release("v3.0.0", title = " ")), ReleasePolicy(versionFrom = VersionFrom.TITLE)).candidate!!
        assertEquals("v3.0.0", untitled.version)
    }

    @Test
    fun theDateCanBeTheVersionAndComparesAsOne() {
        fun at(text: String) = Instant.parse(text).toEpochMilli()
        val releases = listOf(
            Release(id = "a", version = "continuous", publishedAtMs = at("2026-01-02T09:30:00Z"), assets = apk),
            Release(id = "b", version = "continuous", publishedAtMs = at("2026-01-02T14:05:00Z"), assets = apk),
            Release(id = "c", version = "continuous", publishedAtMs = at("2025-12-31T23:59:00Z"), assets = apk),
            Release(id = "d", version = "continuous", assets = apk),
        )
        val policy = ReleasePolicy(versionFrom = VersionFrom.DATE)
        val picked = pick(releases, policy).candidate!!
        assertEquals("b", picked.id)
        assertEquals("2026.01.02.1405", picked.version)
        assertEquals(listOf("b", "a", "c", "d"), tried(releases, policy))
        assertEquals("continuous", pick(listOf(releases[3]), policy).candidate!!.version)
        assertEquals("2026", pick(releases, policy.copy(versionExtract = "^(\\d{4})")).candidate!!.version)
    }

    @Test
    fun releasesCanBeOrderedByDate() {
        val releases = listOf(release("v2.0.0", daysAgo = 30), release("v1.9.1", daysAgo = 2), release("v1.9.0").copy(publishedAtMs = null), release("v1.8.0", daysAgo = 40))
        val policy = ReleasePolicy(order = ReleaseOrder.DATE)
        assertEquals("v1.9.1", pick(releases, policy).candidate!!.id)
        assertEquals(listOf("v1.9.1", "v2.0.0", "v1.8.0", "v1.9.0"), tried(releases, policy))
    }

    @Test
    fun releasesCanKeepTheOrderTheSourceGave() {
        val releases = listOf(release("v1.0.0"), release("v3.0.0"), release("v2.0.0"))
        assertEquals("v1.0.0", pick(releases, ReleasePolicy(order = ReleaseOrder.SOURCE)).candidate!!.id)
        assertEquals(listOf("v1.0.0", "v3.0.0", "v2.0.0"), tried(releases, ReleasePolicy(order = ReleaseOrder.SOURCE)))
        assertEquals("v3.0.0", pick(releases).candidate!!.id)
    }

    @Test
    fun releasesCanBeOrderedByNameWithNumbersReadAsNumbers() {
        val releases = listOf(release("x-2"), release("y-9"), release("Y-10"), release("z"))
        assertEquals(listOf("z", "Y-10", "y-9", "x-2"), tried(releases, ReleasePolicy(order = ReleaseOrder.NAME)))
        assertEquals(listOf("Y-10", "y-9", "x-2", "z"), tried(releases, ReleasePolicy()))
    }

    @Test
    fun theReleaseTheSourceMarksAsLatestComesFirstInEveryOrder() {
        val releases = listOf(release("v3.0.0", daysAgo = 1), release("v2.5.0", daysAgo = 10).copy(latest = true), release("v2.0.0", daysAgo = 20))
        for (order in ReleaseOrder.entries) {
            assertEquals(order.name, "v2.5.0", pick(releases, ReleasePolicy(order = order)).candidate!!.id)
        }
        assertEquals("v3.0.0", pick(releases.map { it.copy(latest = false) }).candidate!!.id)
        assertEquals("v3.0.0", pick(releases, ReleasePolicy(tagFilter = "^v3")).candidate!!.id)
    }

    @Test
    fun staysBehindTheNewestReleasesThatWouldOtherwiseBeChosen() {
        val releases = listOf(release("v4.0.0", assets = emptyList()), release("v3.0.0"), release("v2.0.0"), release("v1.0.0"))
        val one = pick(releases, ReleasePolicy(stayBehind = 1))
        assertEquals("v2.0.0", one.candidate!!.id)
        assertEquals(listOf(Rejection.NO_USABLE_FILE, Rejection.STAY_BEHIND), one.rejected.map { it.second })
        assertEquals("v1.0.0", pick(releases, ReleasePolicy(stayBehind = 2)).candidate!!.id)
        assertNull(pick(releases, ReleasePolicy(stayBehind = 3)).candidate)
        assertEquals("v3.0.0", pick(releases, ReleasePolicy(stayBehind = -1)).candidate!!.id)

        val many = (9 downTo 1).map { release("v$it.0.0") }
        assertEquals("v4.0.0", pick(many, ReleasePolicy(stayBehind = 50)).candidate!!.id)
    }

    @Test
    fun stayingBehindStillKeepsToNoFallback() {
        val releases = listOf(release("v3.0.0"), release("v2.0.0", assets = emptyList()), release("v1.0.0"))
        val strict = pick(releases, ReleasePolicy(stayBehind = 1, fallbackToOlder = false))
        assertNull(strict.candidate)
        assertEquals(listOf(Rejection.STAY_BEHIND, Rejection.NO_USABLE_FILE), strict.rejected.map { it.second })
        assertEquals("v1.0.0", pick(releases, ReleasePolicy(stayBehind = 1)).candidate!!.id)
    }

    @Test
    fun theVersionFilterIsAppliedToTheExtractedVersion() {
        val releases = listOf(release("build-3-v2.1.0"), release("build-2-v2.0.0"), release("build-1-v1.9.0"))
        val picked = pick(releases, ReleasePolicy(versionExtract = "v(\\d+\\.\\d+\\.\\d+)", versionFilter = "^2\\.0\\."))
        assertEquals("build-2-v2.0.0", picked.candidate!!.id)
        assertEquals(listOf(Rejection.VERSION_FILTER, Rejection.VERSION_FILTER), picked.rejected.map { it.second })
        assertNull(pick(releases, ReleasePolicy(versionFilter = "^2\\.0\\.")).candidate)
        assertThrows(PatternException::class.java) { pick(releases, ReleasePolicy(versionFilter = "(unclosed")) }
    }
}
