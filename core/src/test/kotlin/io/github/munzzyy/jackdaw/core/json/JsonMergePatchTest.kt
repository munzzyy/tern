package io.github.munzzyy.jackdaw.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonMergePatchTest {
    private fun merged(target: String, patch: String) = Json.write(JsonMergePatch.apply(Json.parse(target), Json.parse(patch)))

    @Test
    fun followsTheExamplesOfTheStandard() {
        val cases = listOf(
            Triple("""{"a":"b"}""", """{"a":"c"}""", """{"a":"c"}"""),
            Triple("""{"a":"b"}""", """{"b":"c"}""", """{"a":"b","b":"c"}"""),
            Triple("""{"a":"b"}""", """{"a":null}""", """{}"""),
            Triple("""{"a":"b","b":"c"}""", """{"a":null}""", """{"b":"c"}"""),
            Triple("""{"a":["b"]}""", """{"a":"c"}""", """{"a":"c"}"""),
            Triple("""{"a":"c"}""", """{"a":["b"]}""", """{"a":["b"]}"""),
            Triple("""{"a":{"b":"c"}}""", """{"a":{"b":"d","c":null}}""", """{"a":{"b":"d"}}"""),
            Triple("""{"a":[{"b":"c"}]}""", """{"a":[1]}""", """{"a":[1]}"""),
            Triple("""["a","b"]""", """["c","d"]""", """["c","d"]"""),
            Triple("""{"a":"b"}""", """["c"]""", """["c"]"""),
            Triple("""{"a":"foo"}""", """null""", """null"""),
            Triple("""{"a":"foo"}""", """"bar"""", """"bar""""),
            Triple("""{"e":null}""", """{"a":1}""", """{"e":null,"a":1}"""),
            Triple("""[1,2]""", """{"a":"b","c":null}""", """{"a":"b"}"""),
            Triple("""{}""", """{"a":{"bb":{"ccc":null}}}""", """{"a":{"bb":{}}}"""),
        )
        for ((target, patch, expected) in cases) assertEquals("$target + $patch", expected, merged(target, patch))
    }

    @Test
    fun startsFromNothing() {
        assertEquals(Json.parse("""{"a":{"b":1}}"""), JsonMergePatch.apply(null, Json.parse("""{"a":{"b":1,"c":null}}""")))
    }

    @Test
    fun refusesAPatchThatNestsWithoutEnd() {
        var patch: JsonValue = JsonString("x")
        repeat(200) { patch = JsonObject(mapOf("k" to patch)) }
        assertThrows(JsonException::class.java) { JsonMergePatch.apply(null, patch) }
    }
}
