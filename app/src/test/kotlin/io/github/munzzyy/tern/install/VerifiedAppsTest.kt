package io.github.munzzyy.tern.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedAppsTest {
    @Test
    fun everyBuildObtainiumKnowsIsLookedFor() {
        assertEquals(
            setOf("dev.soupslurpr.appverifier", "com.roundsalmon4.appverifier", "org.privacyguides.verifiedapps", "org.privacyguides.verifiedapps.play"),
            VerifiedApps.PACKAGES.toSet(),
        )
        assertEquals("dev.soupslurpr.appverifier", VerifiedApps.first { it == "dev.soupslurpr.appverifier" })
        assertEquals("org.privacyguides.verifiedapps", VerifiedApps.first { true })
        assertNull(VerifiedApps.first { false })
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
