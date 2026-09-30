package io.github.munzzyy.tern.core.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObtainiumLinkTest {
    @Test
    fun parsesAddWithPercentEncodedPath() {
        val encoded = "https%3A%2F%2Fgithub.com%2Fexample%2Fapp"
        val link = ObtainiumLink.parse("obtainium://add/$encoded")
        assertTrue(link is ObtainiumLink.Add)
        assertEquals("https://github.com/example/app", (link as ObtainiumLink.Add).url)
    }

    @Test
    fun parsesAddWithUrlQueryParam() {
        val link = ObtainiumLink.parse("obtainium://add?url=https%3A%2F%2Fexample.com%2Fapp")
        assertEquals("https://example.com/app", (link as ObtainiumLink.Add).url)
    }

    @Test
    fun parsesAppPayload() {
        val json = """{"id":"x","url":"https://example.com"}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        val link = ObtainiumLink.parse("obtainium://app/$encoded")
        assertTrue(link is ObtainiumLink.App)
        assertEquals(json, (link as ObtainiumLink.App).json)
    }

    @Test
    fun parsesAppsPayload() {
        val json = """[{"id":"x"}]"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        val link = ObtainiumLink.parse("obtainium://apps/$encoded")
        assertTrue(link is ObtainiumLink.Apps)
    }

    @Test
    fun readsTheLinkBehindObtainiumsWebPage() {
        val json = """{"id":"x","url":"https://example.com/a b"}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8").replace("+", "%20")
        val shared = ObtainiumLink.parse("https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/$encoded")
        assertEquals(json, (shared as ObtainiumLink.App).json)
        // Some pages encode the carried link as a whole.
        val whole = java.net.URLEncoder.encode("obtainium://app/$encoded", "UTF-8")
        assertEquals(json, (ObtainiumLink.parse("HTTPS://apps.obtainium.imranr.dev/redirect/?r=$whole") as ObtainiumLink.App).json)
    }

    @Test
    fun obtainiumsWebPageWithoutALinkIsNoLink() {
        assertNull(ObtainiumLink.parse("https://apps.obtainium.imranr.dev/redirect?r=https://example.com/app"))
        assertNull(ObtainiumLink.parse("https://apps.obtainium.imranr.dev/redirect?url=obtainium://add/x"))
        assertNull(ObtainiumLink.parse("https://apps.obtainium.imranr.dev/redirected?r=obtainium://add/x"))
    }

    @Test
    fun returnsNullForWrongScheme() {
        assertNull(ObtainiumLink.parse("https://example.com/add/x"))
    }

    @Test
    fun returnsNullForUnknownAction() {
        assertNull(ObtainiumLink.parse("obtainium://refresh"))
    }

    @Test
    fun returnsNullForGarbageInput() {
        assertNull(ObtainiumLink.parse("::::not a uri::::"))
    }
}
