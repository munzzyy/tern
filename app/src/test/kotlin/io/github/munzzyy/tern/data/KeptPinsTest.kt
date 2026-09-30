package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.real.RealEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptPinsTest {
    private val pin = "a894d999" + "0".repeat(56)
    private val other = "b".repeat(64)
    private val key = "c".repeat(64)
    private val github = AppConfig("a", SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), "App", pinnedSigners = listOf(pin))

    @Test
    fun aDroppedAppAddedAgainIsHeldToWhatItWasHeldTo() {
        val kept = KeptPins.Kept.decode(KeptPins.Kept.of(github, 5)!!.encode())!!
        assertEquals(5L, kept.atMs)
        // Added again, the first file served would otherwise become the pin.
        val again = kept.onto(github.copy(pinnedSigners = emptyList()))
        assertEquals(listOf(pin), again.pinnedSigners)
    }

    @Test
    fun pinsTheAppAlreadyHasAreNeverReplaced() {
        val kept = KeptPins.Kept(listOf(pin), null)
        assertEquals(listOf(other), kept.onto(github.copy(pinnedSigners = listOf(other))).pinnedSigners)
    }

    @Test
    fun theKeyOfARepositoryComesBackToo() {
        val repo = AppConfig(
            "r", SourceSpec(SourceTypes.FDROID_REPO, "https://repo.example.org/repo", mapOf(SourceOptions.PACKAGE to "org.example", SourceOptions.FINGERPRINT to key)), "Repo",
        )
        val kept = KeptPins.Kept.of(repo, 1)!!
        val bare = repo.copy(source = repo.source.copy(options = mapOf(SourceOptions.PACKAGE to "org.example")))
        assertEquals(key, kept.onto(bare).source.option(SourceOptions.FINGERPRINT))
    }

    @Test
    fun anAppHeldToNothingLeavesNothing() {
        assertNull(KeptPins.Kept.of(github.copy(pinnedSigners = emptyList()), 1))
        assertNull(KeptPins.Kept.decode("{\"signers\":[\"not a hash\"]}"))
        assertNull(KeptPins.Kept.decode("not json"))
    }

    @Test
    fun eachAppOfARepositoryIsKeptApart() {
        val one = SourceSpec(SourceTypes.FDROID_REPO, "https://Repo.example.org/repo", mapOf(SourceOptions.PACKAGE to "org.one"))
        val two = one.copy(options = mapOf(SourceOptions.PACKAGE to "org.two"))
        assertNotEquals(KeptPins.key(one), KeptPins.key(two))
        assertEquals(KeptPins.key(github.source), KeptPins.key(github.source.copy(url = "https://GitHub.com/example/app")))
    }

    @Test
    fun archivingAnAppOrReplacingItIsNoUninstall() {
        val removed = "android.intent.action.PACKAGE_REMOVED"
        assertTrue(RealEngine.removedForGood(removed, replacing = false, archival = false))
        assertFalse(RealEngine.removedForGood(removed, replacing = false, archival = true))
        assertFalse(RealEngine.removedForGood(removed, replacing = true, archival = false))
        assertFalse(RealEngine.removedForGood("android.intent.action.PACKAGE_ADDED", replacing = false, archival = false))
    }
}
