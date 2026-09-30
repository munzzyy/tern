package io.github.munzzyy.tern.engine.real

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedFileNameTest {
    @Test
    fun aSavedFileKeepsItsNameWithoutFolders() {
        assertEquals("app-arm64.apk", savedName("app-arm64.apk"))
        assertEquals("_.._evil.apk", savedName("/../evil.apk"))
        assertEquals("hidden.apk", savedName(".hidden.apk"))
        assertEquals("download.bin", savedName("  "))
        assertEquals(120, savedName("x".repeat(300)).length)
    }

    @Test
    fun eachKindOfFileIsListedAsWhatItIs() {
        assertEquals("application/vnd.android.package-archive", mimeOf("App.APK"))
        assertEquals("application/zip", mimeOf("bundle.apks"))
        assertEquals("application/gzip", mimeOf("app.tar.gz"))
        assertEquals("application/octet-stream", mimeOf("notes.txt"))
    }
}
