package io.github.munzzyy.tern.core.interop

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressListTest {
    @Test
    fun oneAddressALineAsAListIsWritten() {
        val text = """
            https://github.com/example/app
            https://codeberg.org/example/other/

            https://f-droid.org/packages/org.example.third
        """.trimIndent()
        assertEquals(
            listOf("https://github.com/example/app", "https://codeberg.org/example/other/", "https://f-droid.org/packages/org.example.third"),
            AddressList.read(text),
        )
    }

    @Test
    fun addressesAreFoundInsideSentencesAndOpml() {
        val text = """
            Try https://github.com/example/app. And (see https://gitlab.com/example/tool)!
            <outline text="x" xmlUrl="https://example.org/feed?a=1&amp;b=2" htmlUrl="https://example.org/app"/>
        """.trimIndent()
        assertEquals(
            listOf("https://github.com/example/app", "https://gitlab.com/example/tool", "https://example.org/feed?a=1&b=2", "https://example.org/app"),
            AddressList.read(text),
        )
    }

    @Test
    fun plainHttpAndRepeatsAreLeftOutAndTheListIsCapped() {
        val text = "http://example.org/a https://example.org/b https://example.org/b https://example.org/c"
        assertEquals(listOf("https://example.org/b", "https://example.org/c"), AddressList.read(text))
        assertEquals(1, AddressList.read(text, max = 1).size)
    }

    @Test
    fun aBracketThatBelongsToTheAddressStays() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Tern_(bird)"), AddressList.read("see https://en.wikipedia.org/wiki/Tern_(bird)."))
    }
}
