package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.version.Stage
import io.github.munzzyy.stamp.core.version.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionGuessTest {
    @Test
    fun plainVersionIsFoundUnchanged() {
        assertEquals("3.4.5", VersionGuess.find("app-3.4.5-release.apk"))
    }

    @Test
    fun stageAttachedDirectlyToNumberIsKept() {
        assertEquals("1.2.3-beta2", VersionGuess.find("app-1.2.3-beta2.apk"))
    }

    @Test
    fun stageSeparatedByACodenameIsNotCutOff() {
        val guess = VersionGuess.find("kodi-22.0-Piers_beta1-armeabi-v7a.apk")
        assertEquals("22.0-beta1", guess)
        val parsed = Version.parse(guess!!)
        assertEquals(Stage.BETA, parsed.stage)
        assertEquals(1L, parsed.stageNumber)
    }

    @Test
    fun betaTwoBeatsBetaOneOnceTheCodenameIsSkipped() {
        val v1 = Version.parse(VersionGuess.find("kodi-22.0-Piers_beta1-armeabi-v7a.apk")!!)
        val v2 = Version.parse(VersionGuess.find("kodi-22.0-Piers_beta2-armeabi-v7a.apk")!!)
        assertTrue(v1 < v2)
    }

    @Test
    fun productNameThatLooksLikeAStageWordIsNotReadAsOne() {
        assertEquals("11.0", VersionGuess.find("developer-preview-11.0-x86.apk"))
        assertEquals("2.1.0", VersionGuess.find("prefs-tool-2.1.0-arch64.apk"))
    }

    @Test
    fun realPreviewStageIsStillRead() {
        assertEquals("11.0-preview2", VersionGuess.find("app-11.0-preview2-x86.apk"))
    }

    @Test
    fun processorNameAfterTheVersionIsNotPulledIn() {
        assertEquals("4.0.0", VersionGuess.find("app-4.0.0-armeabi-v7a.apk"))
    }

    @Test
    fun fileEndingAfterTheVersionIsNotPulledIn() {
        assertEquals("5.6.0", VersionGuess.find("app-5.6.0.tar.gz"))
    }

    @Test
    fun noNumberFindsNothing() {
        assertNull(VersionGuess.find("app-latest-release.apk"))
    }

    @Test
    fun inputIsCapped() {
        val huge = "x".repeat(5000) + "9.9.9"
        assertNull(VersionGuess.find(huge))
    }

    @Test
    fun aTagOfThePageAfterTheNumberIsNotReadAsAStage() {
        assertEquals("1.2.3", VersionGuess.find("<b>Version 1.2.3</b><pre>notes</pre>"))
        assertEquals("1.2.3", VersionGuess.find("1.2.3</b><pre>"))
        assertEquals("4.5.6", VersionGuess.find("<td>4.5.6</td><td class=\"rc\">"))
    }

    @Test
    fun wordsAfterASpaceAreNotReadAsAStage() {
        assertEquals("1.2.3", VersionGuess.find("1.2.3 for dev boards"))
        assertEquals("2.0.1", VersionGuess.find("Release 2.0.1, the beta is over"))
        assertEquals("3.1.0", VersionGuess.find("3.1.0 (nightly builds are elsewhere)"))
    }

    @Test
    fun anotherPartOfTheAddressIsNotReadAsAStage() {
        assertEquals("1.2.3", VersionGuess.find("files/1.2.3/dev/app.apk"))
        assertEquals("1.2.3", VersionGuess.find("app-1.2.3.apk?channel=beta"))
    }

    @Test
    fun theStageIsStillReadThroughDotsDashesAndUnderscores() {
        assertEquals("1.2.3-rc1", VersionGuess.find("app_1.2.3_rc1.apk"))
        assertEquals("1.2.3-alpha", VersionGuess.find("app-1.2.3.alpha.apk"))
        assertEquals("7.0-nightly", VersionGuess.find("app-7.0-20260101-nightly.apk"))
    }
}
