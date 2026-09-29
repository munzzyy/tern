package io.github.munzzyy.tern.core.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlScannerTest {
    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- a feed -->
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
          <id>tag:example.org,2008:https://example.org/example/app/releases</id>
          <link type="text/html" rel="alternate" href="https://example.org/example/app/releases"/>
          <updated>2026-09-01T10:00:00Z</updated>
          <entry>
            <id>tag:example.org,2008:Repository/1/v1.2.0</id>
            <title>Version 1.2 &amp; fixes</title>
            <content type="html">&lt;p&gt;Notes &#8212; here &#x1F600;&lt;/p&gt;</content>
            <media:thumbnail height="30" width="30" url="https://example.org/a.png?s=60&amp;v=4"/>
          </entry>
          <entry>
            <id>tag:example.org,2008:Repository/1/v1.1.0</id>
            <title><![CDATA[1.1 <raw> & unescaped]]></title>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsAnAtomFeed() {
        val feed = XmlScanner.parse(atom)
        assertEquals("feed", feed.localName)
        assertEquals("en-US", feed.attributes["xml:lang"])
        assertEquals("2026-09-01T10:00:00Z", feed.childText("updated"))
        val entries = feed.children("entry")
        assertEquals(2, entries.size)
        assertEquals("Version 1.2 & fixes", entries[0].childText("title"))
        assertEquals("<p>Notes — here 😀</p>", entries[0].childText("content"))
        assertEquals("https://example.org/a.png?s=60&v=4", entries[0].child("thumbnail")!!.attributes["url"])
        assertEquals("1.1 <raw> & unescaped", entries[1].childText("title"))
        assertNull(entries[1].childText("content"))
    }

    @Test
    fun neverExpandsDeclaredEntities() {
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
              <!ENTITY big "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            ]>
            <r>&xxe;&big;</r>
        """.trimIndent()
        assertEquals("&xxe;&big;", XmlScanner.parse(bomb).text)
    }

    @Test
    fun rejectsBrokenDocuments() {
        val bad = listOf(
            "", "plain text", "<a>", "<a></b>", "<a><b></a></b>", "<a b></a>", "<a b=c></a>", "<a b=\"c></a>",
            "<a/><b/>", "<a>text", "<a><!-- x</a>", "<a><![CDATA[x</a>", "<a></a>trailing", "<!DOCTYPE a [", "<a><!ELEMENT x></a>",
        )
        for (text in bad) assertThrows("should reject: $text", XmlException::class.java) { XmlScanner.parse(text) }
    }

    @Test
    fun limitsNesting() {
        val deep = "<a>".repeat(200) + "</a>".repeat(200)
        assertThrows(XmlException::class.java) { XmlScanner.parse(deep) }
    }

    @Test
    fun leavesUnknownAndMalformedReferencesAlone() {
        assertEquals("a &nbsp; b & c &#xD800; &#0; &#99999999;", XmlScanner.decode("a &nbsp; b & c &#xD800; &#0; &#99999999;"))
        assertEquals("<>&\"'", XmlScanner.decode("&lt;&gt;&amp;&quot;&apos;"))
    }
}
