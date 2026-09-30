package io.github.munzzyy.tern.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A copy of Tern that F-Droid installed keeps getting F-Droid's builds. */
class SelfSourceTest {
    @Test
    fun aCopyAnFDroidClientInstalledFollowsFDroid() {
        for (client in listOf("org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged", "com.looker.droidify", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic")) {
            assertEquals(client, FDROID_URL, selfSource(client))
        }
    }

    @Test
    fun anyOtherCopyFollowsGitHub() {
        for (installer in listOf(null, "com.google.android.packageinstaller", "com.android.shell", "io.github.munzzyy.tern", "dev.imranr.obtainium")) {
            assertEquals(installer.toString(), SOURCE_URL, selfSource(installer))
        }
    }

    @Test
    fun followingFDroidCountsAsTracked() {
        assertTrue(tracksItself(listOf(FDROID_URL to null), "io.github.munzzyy.tern"))
        assertTrue(tracksItself(listOf("https://github.com/munzzyy/tern/" to null), "io.github.munzzyy.tern"))
        assertFalse(tracksItself(listOf("https://github.com/example/app" to "org.example"), "io.github.munzzyy.tern"))
    }

    @Test
    fun choosingGitHubOnAnFDroidCopySaysItSkipsFDroidsChecks() {
        val text = File("src/main/res/values/strings_safety.xml").readText()
        val github = Regex("""name="about_track_tern_github_effect">([^<]*)<""").find(text)!!.groupValues[1]
        assertTrue(github, "skips F-Droid\\'s checks" in github)
        val fdroid = Regex("""name="about_track_tern_fdroid_effect">([^<]*)<""").find(text)!!.groupValues[1]
        assertTrue(fdroid, "F-Droid made and checked" in fdroid)
    }
}
