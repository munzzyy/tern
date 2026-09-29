package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.net.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ServedFileTest {
    private fun name(header: String?) = ServedFile.dispositionName(header)

    @Test
    fun readsQuotedUnquotedAndExtendedNames() {
        assertEquals("Example.apk", name("attachment; filename=\"Example.apk\""))
        assertEquals("Example.apk", name("attachment;filename=Example.apk"))
        assertEquals("na\u00efve app.apk", name("attachment; filename=\"fallback.apk\"; filename*=UTF-8''na%C3%AFve%20app.apk"))
        assertEquals("a+b.apk", name("attachment; filename*=utf-8''a+b.apk"))
        assertEquals("say \"hi\".apk", name("attachment; filename=\"say \\\"hi\\\".apk\""))
        assertEquals("x.apk", name("attachment; junk; filename=x.apk"))
    }

    @Test
    fun keepsOnlyTheLastPathPart() {
        assertEquals("x.apk", name("attachment; filename=\"../../data/x.apk\""))
        assertEquals("x.apk", name("attachment; filename=\"C:\\\\temp\\\\x.apk\""))
        assertNull(name("attachment; filename=\"..\""))
        assertNull(name("attachment; filename=\"dir/\""))
    }

    @Test
    fun dropsCharactersThatDisguiseANameOnScreen() {
        assertEquals("evilkpa.exe", name("attachment; filename=\"evil\u202Ekpa.exe\""))
        assertEquals("ab.apk", name("attachment; filename=\"a\u0000b\r\n.apk\""))
    }

    @Test
    fun survivesBrokenHeaders() {
        assertNull(name(null))
        assertNull(name(""))
        assertNull(name("attachment"))
        assertNull(name("attachment; filename=\"unterminated.apk"))
        assertNull(name("attachment; filename*=UTF-8''%ZZ.apk"))
        assertNull(name("attachment; filename*=KOI8-R''x.apk"))
        assertNull(name("attachment; filename*=nonsense"))
        assertNull(name("attachment; filename=\"" + "a".repeat(5000) + ".apk\""))
        assertNull(name(";;;;=;=\"\\"))
        val random = Random(7)
        val alphabet = "; =\"\\'%afilename*.apk"
        repeat(2000) {
            val chars = CharArray(random.nextInt(0, 80)) { alphabet[random.nextInt(alphabet.length)] }
            name(String(chars))
        }
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val long = name("attachment; filename=\"" + "a".repeat(400) + ".apk\"")
        assertEquals(200, long?.length)
        assertTrue(long!!.endsWith(".apk"))
    }

    @Test
    fun announcedNeedsTypeOrDisposition() {
        val final = "https://cdn.example.org/Example.apk"
        assertNull(ServedFile.announced(Headers.of("Content-Type" to "application/octet-stream"), final))
        assertEquals(ServedFile("Example.apk", AssetKind.APK), ServedFile.announced(Headers.of("Content-Type" to "Application/Vnd.Android.Package-Archive"), final))
        assertEquals(AssetKind.BUNDLE, ServedFile.announced(Headers.of("Content-Disposition" to "attachment; filename=app.apks"), final)?.kind)
        assertNull(ServedFile.announced(Headers.of("Content-Disposition" to "attachment; filename=app.apk.txt"), final))
        assertEquals("download.apk", ServedFile.announced(Headers.of("Content-Type" to "application/vnd.android.package-archive"), "")?.name)
    }

    @Test
    fun typeCheckIgnoresParametersButNotLookalikes() {
        assertTrue(ServedFile.isApkType("application/vnd.android.package-archive;q=1"))
        assertFalse(ServedFile.isApkType("application/vnd.android.package-archive-not"))
        assertFalse(ServedFile.isApkType(null))
        assertFalse(ServedFile.isApkType("application/vnd.android.package-archive" + " ".repeat(3000)))
    }
}
