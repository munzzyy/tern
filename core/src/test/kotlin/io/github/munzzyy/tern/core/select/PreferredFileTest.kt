package io.github.munzzyy.tern.core.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredFileTest {
    private fun find(picked: String, vararg names: String): String? = PreferredFile.find(picked, names.toList()) { it }

    @Test
    fun theSameFileOfANewerVersionIsFound() {
        assertEquals(
            "app-arm64-v8a-release-2.2.1.apk",
            find("app-arm64-v8a-release-2.1.apk", "app-x86_64-release-2.2.1.apk", "app-arm64-v8a-release-2.2.1.apk", "app-armeabi-v7a-release-2.2.1.apk"),
        )
    }

    @Test
    fun theProcessorANameNamesIsPartOfItsShape() {
        assertNull(find("app-arm64-v8a-2.1.apk", "app-x86_64-2.2.apk", "app-armeabi-v7a-2.2.apk"))
        assertEquals("app-x86-2.2.apk", find("app-x86-2.1.apk", "app-x86_64-2.2.apk", "app-x86-2.2.apk"))
        assertEquals("app-x86_64-2.2.apk", find("app-x86_64-2.1.apk", "app-x86-2.2.apk", "app-x86_64-2.2.apk"))
    }

    @Test
    fun aFlavourIsPartOfItsShape() {
        assertEquals("Example-foss-1.4.apk", find("Example-foss-1.3.apk", "Example-gplay-1.4.apk", "Example-foss-1.4.apk"))
        assertEquals("app-universal-3.apk", find("app-universal-2.apk", "app-3.apk", "app-universal-3.apk"))
    }

    @Test
    fun aVersionWithMorePartsIsStillAVersion() {
        assertEquals("NewPipe_v0.28.0.1.apk", find("NewPipe_v0.27.apk", "NewPipe_v0.28.0.1.apk"))
        assertEquals("tool-2026-10-01.apk", find("tool-2026-09-01.apk", "tool-2026-10-01.apk"))
    }

    @Test
    fun ofSeveralOfOneShapeTheOneWhoseNumbersAgreeWins() {
        assertEquals(
            "app-minApi21-1.2.4.apk",
            find("app-minApi21-1.2.3.apk", "app-minApi26-1.2.4.apk", "app-minApi21-1.2.4.apk"),
        )
        assertEquals("a-1.apk", find("a-9.apk", "a-1.apk", "a-2.apk"))
    }

    @Test
    fun theSameNameIsFoundWhateverElseIsThere() {
        assertEquals("app-release.apk", find("app-release.apk", "app-debug.apk", "app-release.apk"))
        assertEquals("App-Release.APK", find("app-release.apk", "App-Release.APK"))
    }

    @Test
    fun nothingOfTheShapeIsNothing() {
        assertNull(find("app-release.apk", "app-release.apks"))
        assertNull(find("app-release.apk"))
        assertNull(find("tool-1.0.apk", "tool-cli-1.1.apk"))
    }
}
