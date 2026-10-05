package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.model.Release
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleasesSinceTest {
    private fun rel(version: String, code: Long? = null, notes: String? = "Changes in $version") =
        Release(id = "v$version${code?.let { "-$it" }.orEmpty()}", version = version, versionCode = code, notes = notes)

    private fun installed(version: String, code: Long = 1) = InstalledApp("org.example.app", version, code, emptyList())

    private val listing = listOf(rel("1.3"), rel("1.2"), rel("1.1"), rel("1.0"), rel("0.9"))

    private fun versions(releases: List<Release>) = releases.map { it.version }

    @Test
    fun everyReleaseSinceTheInstalledOneIsListedNewestFirst() {
        assertEquals(listOf("1.3", "1.2", "1.1"), versions(releasesSince(listing, "v1.3", installed("1.0"))))
        assertEquals(listOf("1.3", "1.2", "1.1"), versions(releasesSince(listing, "v1.3", installed("v1.0"))))
    }

    @Test
    fun theListStartsAtTheOfferedRelease() {
        assertEquals(listOf("1.2", "1.1"), versions(releasesSince(listing, "v1.2", installed("1.0"))))
    }

    @Test
    fun nothingWhenTheInstalledReleaseIsNotListed() {
        assertEquals(emptyList<Release>(), releasesSince(listing, "v1.3", installed("0.5")))
        assertEquals(emptyList<Release>(), releasesSince(listing, "v1.3", null))
        assertEquals(emptyList<Release>(), releasesSince(listing, "v9.9", installed("1.0")))
    }

    @Test
    fun nothingWhenTheOfferedReleaseIsTheInstalledOne() {
        assertEquals(emptyList<Release>(), releasesSince(listing, "v1.0", installed("1.0")))
    }

    @Test
    fun noMoreThanTheLimitAreListed() {
        assertEquals(listOf("1.3", "1.2"), versions(releasesSince(listing, "v1.3", installed("0.9"), limit = 2)))
        val many = (40 downTo 1).map { rel("1.$it") }
        assertEquals(10, releasesSince(many, "v1.40", installed("1.1")).size)
    }

    @Test
    fun theVersionCodeSettlesWhichReleaseIsInstalledBeforeTheText() {
        val coded = listOf(rel("1.3", 13), rel("1.2", 12), rel("1.1-renamed", 11), rel("1.0", 10))
        assertEquals(listOf("1.3", "1.2"), versions(releasesSince(coded, "v1.3-13", installed("1.1", code = 11))))
    }

    @Test
    fun releasesWithoutNotesAreLeftOut() {
        val quiet = listOf(rel("1.3"), rel("1.2", notes = " "), rel("1.1", notes = null), rel("1.0"))
        assertEquals(listOf("1.3"), versions(releasesSince(quiet, "v1.3", installed("1.0"))))
    }

    @Test
    fun aStoresBuildsForOtherProcessorsAreNotRepeatedAndTheInstalledVersionEndsTheList() {
        val builds = listOf(
            rel("3.7.1", 13070108), rel("3.7.1", 13070107), rel("3.7.1", 13070105),
            rel("3.7.0", 13070008), rel("3.7.0", 13070007), rel("3.7.0", 13070005),
            rel("3.6.5", 13060508), rel("3.6.5", 13060505),
        )
        assertEquals(listOf("3.7.1", "3.7.0"), versions(releasesSince(builds, "v3.7.1-13070105", installed("3.6.5", code = 13060505))))
        assertEquals(listOf("3.7.1"), versions(releasesSince(builds, "v3.7.1-13070105", installed("3.7.0", code = 13070005))))
    }
}
