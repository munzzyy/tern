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
