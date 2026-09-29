package io.github.munzzyy.stamp.core.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconAddressesTest {
    private val forge = "https://codeberg.org/example/app"

    @Test
    fun addressesOnTheSourcesOwnHostAreKeptInTheirOrder() {
        val named = listOf("https://codeberg.org/repo-avatars/one", null, "https://codeberg.org/avatars/two?size=96")
        assertEquals(listOf("https://codeberg.org/repo-avatars/one", "https://codeberg.org/avatars/two?size=96"), IconAddresses.accepted(forge, named))
    }

    @Test
    fun anAddressThatIsNotHttpsIsDropped() {
        val named = listOf(
            "http://codeberg.org/avatars/plain",
            "//codeberg.org/avatars/no-scheme",
            "codeberg.org/avatars/bare",
            "ftp://codeberg.org/avatars/other",
            "data:image/png;base64,AAAA",
            "file:///data/data/icon.png",
            "content://codeberg.org/icon",
            "",
            "   ",
        )
        assertEquals(emptyList<String>(), IconAddresses.accepted(forge, named))
    }

    @Test
    fun anAddressThatCannotBeReadOrCarriesALoginIsDropped() {
        val named = listOf(
            "https://user:secret@codeberg.org/avatars/one",
            "https://codeberg.org/avatars/with space",
            "https:///avatars/no-host",
            "https://codeberg.org/" + "a".repeat(IconAddresses.MAX_LENGTH),
        )
        assertEquals(emptyList<String>(), IconAddresses.accepted(forge, named))
    }

    @Test
    fun anotherHostIsDroppedEvenWhenItLooksLikeTheSource() {
        val named = listOf(
            "https://pictures.example.net/icon.png",
            "https://codeberg.org.example.net/icon.png",
            "https://notcodeberg.org/icon.png",
            "https://sub.codeberg.org/icon.png",
            "https://raw.githubusercontent.com/example/app/HEAD/icon.png",
        )
        assertEquals(emptyList<String>(), IconAddresses.accepted(forge, named))
    }

    @Test
    fun gitHubMayNameItsTwoPictureHostsAndNoOther() {
        val named = listOf(
            "https://raw.githubusercontent.com/example/app/HEAD/fastlane/metadata/android/en-US/images/icon.png",
            "https://avatars.githubusercontent.com/example?s=192",
            "https://objects.githubusercontent.com/example/icon.png",
            "https://github.com/example.png",
        )
        assertEquals(listOf(named[0], named[1], named[3]), IconAddresses.accepted("https://github.com/example/app", named))
    }

    @Test
    fun theHostsAllowedBesidesTheSourcesOwnAreListedInOnePlace() {
        assertEquals(
            mapOf("github.com" to setOf("raw.githubusercontent.com", "avatars.githubusercontent.com")),
            IconAddresses.OTHER_HOSTS,
        )
    }

    @Test
    fun theSameAddressIsKeptOnceAndTheListIsCapped() {
        val twice = listOf("https://codeberg.org/a", "https://CODEBERG.org/a", "https://codeberg.org:443/a")
        assertEquals(listOf("https://codeberg.org/a"), IconAddresses.accepted(forge, twice))

        val many = List(20) { "https://codeberg.org/avatars/$it" }
        assertEquals(many.take(IconAddresses.MAX_ADDRESSES), IconAddresses.accepted(forge, many))
    }

    @Test
    fun aListingThatNamesAnIconReplacesTheOneKnown() {
        val stored = IconAddresses.afterCheck(forge, listOf("https://codeberg.org/new"), listOf("https://codeberg.org/old"))
        assertEquals(listOf("https://codeberg.org/new"), stored)
    }

    @Test
    fun aListingThatNamesNothingUsableKeepsTheIconKnown() {
        val known = listOf("https://codeberg.org/old", "https://codeberg.org/older")
        assertEquals(known, IconAddresses.afterCheck(forge, emptyList(), known))
        assertEquals(known, IconAddresses.afterCheck(forge, listOf("http://codeberg.org/plain", "https://elsewhere.example.net/a"), known))
    }

    @Test
    fun theIconKnownDoesNotOutliveAMoveToAnotherHost() {
        val known = listOf("https://codeberg.org/old", "https://git.example.org/avatars/kept")
        assertEquals(listOf("https://git.example.org/avatars/kept"), IconAddresses.afterCheck("https://git.example.org/example/app", emptyList(), known))
    }

    @Test
    fun aSourceWithoutAHostNamesNothing() {
        assertTrue(IconAddresses.accepted("not an address", listOf("https://codeberg.org/a")).isEmpty())
    }
}
