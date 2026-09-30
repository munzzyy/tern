package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedAppsTest {
    private val genuine: (String) -> List<String>? = { listOfNotNull(VerifiedApps.CERTIFICATES[it]) }

    @Test
    fun everyBuildObtainiumKnowsIsLookedFor() {
        assertEquals(
            setOf("dev.soupslurpr.appverifier", "com.roundsalmon4.appverifier", "org.privacyguides.verifiedapps", "org.privacyguides.verifiedapps.play"),
            VerifiedApps.PACKAGES.toSet(),
        )
        assertEquals("dev.soupslurpr.appverifier", VerifiedApps.first(genuine) { it == "dev.soupslurpr.appverifier" })
        assertEquals("org.privacyguides.verifiedapps", VerifiedApps.first(genuine) { true })
        assertNull(VerifiedApps.first(genuine) { false })
    }

    @Test
    fun eachIsPinnedToTheCertificateItsMakersPublish() {
        assertEquals(VerifiedApps.PACKAGES.toSet(), VerifiedApps.CERTIFICATES.keys)
        assertEquals("405c6bd2ca7c3aae8f463c6f8b55bcf0ddac431c5ed8eaff65d106c9817a207f", VerifiedApps.CERTIFICATES["org.privacyguides.verifiedapps"])
        assertEquals("3a04a80b2a88334c747485f0b2151640a38bb3d2d73a8eab81df503e0f0202b2", VerifiedApps.CERTIFICATES["dev.soupslurpr.appverifier"])
        for (hash in VerifiedApps.CERTIFICATES.values) assertTrue(hash, hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun anAppThatTookTheNameButNotTheCertificateGetsNothing() {
        val impostor = "aa".repeat(32)
        val signers = { pkg: String -> if (pkg == "org.privacyguides.verifiedapps") listOf(impostor) else genuine(pkg) }
        assertEquals("org.privacyguides.verifiedapps.play", VerifiedApps.first(signers) { true })
        assertNull(VerifiedApps.first({ listOf(impostor) }) { true })
        assertNull(VerifiedApps.first({ null }) { true })
        assertFalse(VerifiedApps.genuine("org.privacyguides.verifiedapps", emptyList()))
        assertFalse(VerifiedApps.genuine("org.example.other", listOf(impostor)))
    }

    @Test
    fun theHandOffIsOffUntilThePersonTurnsItOn() {
        assertFalse(Settings().shareToVerifier)
    }

    @Test
    fun onlyTheFirstInstallOfAnAppGoesThereAndOnlyWithSomeoneToLookAtIt() {
        val verifier = "org.privacyguides.verifiedapps"
        assertTrue(VerifiedApps.handsOver(setting = true, firstInstall = true, there = true, verifier = verifier))
        assertFalse(VerifiedApps.handsOver(setting = false, firstInstall = true, there = true, verifier = verifier))
        assertFalse(VerifiedApps.handsOver(setting = true, firstInstall = false, there = true, verifier = verifier))
        assertFalse(VerifiedApps.handsOver(setting = true, firstInstall = true, there = false, verifier = verifier))
        assertFalse(VerifiedApps.handsOver(setting = true, firstInstall = true, there = true, verifier = null))
    }

    @Test
    fun theAboutPageIsTheOneObtainiumLinks() {
        assertEquals("https://github.com/privacyguides/verified-apps-android", VerifiedApps.ABOUT)
    }
}
