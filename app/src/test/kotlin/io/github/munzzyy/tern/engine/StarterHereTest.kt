package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.core.suggest.SuggestedApp
import io.github.munzzyy.tern.core.suggest.SuggestedKind
import io.github.munzzyy.tern.engine.real.Suggestions
import org.junit.Assert.assertEquals
import org.junit.Test

class StarterHereTest {
    private val developer = "a".repeat(64)
    private val rotatedFrom = "b".repeat(64)
    private val store = "c".repeat(64)
    private val carried = SuggestedApp(
        "Example", "https://github.com/example/app", SuggestedKind.TOOLS, television = false, summaryKey = "example",
        packageName = "org.example.app", signers = listOf(developer),
    )
    private val unpinned = carried.copy(signers = emptyList())

    @Test
    fun anAppInTheListIsSaidToBeThereWhateverIsInstalled() {
        assertEquals(StarterHere.IN_LIST, Suggestions.here(carried, followed = true, installedSigners = listOf(store)))
    }

    @Test
    fun anAppNotOnThePhoneIsOfferedPlainly() {
        assertEquals(StarterHere.NONE, Suggestions.here(carried, followed = false, installedSigners = null))
    }

    @Test
    fun theDevelopersOwnCopyIsOnThePhone() {
        assertEquals(StarterHere.ON_PHONE, Suggestions.here(carried, followed = false, installedSigners = listOf(developer.uppercase())))
    }

    @Test
    fun aCopyWhoseKeyRotatedFromTheDevelopersCounts() {
        assertEquals(StarterHere.ON_PHONE, Suggestions.here(carried, followed = false, installedSigners = listOf(store, developer)))
    }

    @Test
    fun aCopySignedBySomeoneElseIsSaidToBe() {
        assertEquals(StarterHere.ON_PHONE_OTHER_SIGNER, Suggestions.here(carried, followed = false, installedSigners = listOf(store, rotatedFrom)))
    }

    @Test
    fun withoutACarriedCertificateAnInstalledCopyIsOnlyOnThePhone() {
        assertEquals(StarterHere.ON_PHONE, Suggestions.here(unpinned, followed = false, installedSigners = listOf(store)))
    }
}
