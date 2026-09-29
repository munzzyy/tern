package io.github.munzzyy.stamp.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class JsonTest {
    @Test
    fun parsesNestedDocument() {
        val doc = Json.parseObject("""{"a":[1,2.5,-3e2,true,false,null,"x"],"b":{"c":"d"},"e":""}""")
        val a = doc.array("a")!!
        assertEquals(1L, (a[0] as JsonNumber).toLongOrNull())
        assertEquals(2.5, (a[1] as JsonNumber).toDoubleOrNull()!!, 0.0)
        assertEquals(-300L, (a[2] as JsonNumber).toLongOrNull())
        assertEquals(JsonBool(true), a[3])
        assertEquals(JsonNull, a[5])
        assertEquals("d", doc.obj("b")!!.string("c"))
        assertEquals("", doc.string("e"))
        assertNull(doc.string("missing"))
    }

    @Test
    fun decodesEscapesAndSurrogates() {
        val s = (Json.parse("\"a\\n\\t\\\"\\\\\\/\\u00e9\\ud83d\\ude00\"") as JsonString).value
        assertEquals("a\n\t\"\\/\u00e9\ud83d\ude00", s)
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf(
            "", " ", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "[1 2]", "01", "1.", ".5", "-", "+1", "1e", "tru", "nul",
            "\"abc", "\"a\u0001b\"", "\"\\x\"", "\"\\u12g4\"", "[1]]", "{}{}", "{\"a\"}", "{\"a\":}", "[,1]", "NaN", "truex",
        )
        for (text in bad) {
            assertThrows("should reject: $text", JsonException::class.java) { Json.parse(text) }
        }
    }

    @Test
    fun enforcesDepthLimit() {
        val deep = "[".repeat(100) + "]".repeat(100)
        assertThrows(JsonException::class.java) { Json.parse(deep) }
        val ok = "[".repeat(40) + "]".repeat(40)
        assertTrue(Json.parse(ok) is JsonArray)
    }

    @Test
    fun skipsValuesWhileStreaming() {
        val reader = JsonReader(StringReader("""{"skip":{"x":[1,{"y":[]}],"z":"s"},"n":7,"also":[[],[{}]],"want":"yes"}"""))
        var want: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "want" -> want = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        reader.requireEndOfDocument()
        assertEquals("yes", want)
    }

    @Test
    fun streamsAcrossBufferBoundary() {
        val big = "x".repeat(20_000)
        val doc = Json.parseObject("{\"k\":\"$big\",\"n\":12345678901}")
        assertEquals(big, doc.string("k"))
        assertEquals(12345678901L, doc.long("n"))
    }

    @Test
    fun writesAndReadsBack() {
        val value = Json.obj("s" to "line\n\"q\"\u2028", "n" to 5, "b" to true, "z" to null, "l" to listOf(1, "two"), "m" to mapOf("k" to "v"))
        assertEquals(value, Json.parse(Json.write(value)))
        assertEquals(value, Json.parse(Json.write(value, indent = true)))
        assertEquals("""{"a":[]}""", Json.write(Json.obj("a" to emptyList<Int>())))
    }

    @Test
    fun numbersKeepPrecisionAsText() {
        val n = Json.parse("123456789012345678901234567890") as JsonNumber
        assertEquals("123456789012345678901234567890", n.raw)
        assertNull(n.toLongOrNull())
    }
}
