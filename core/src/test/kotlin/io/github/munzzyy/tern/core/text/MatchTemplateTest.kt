package io.github.munzzyy.tern.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTemplateTest {
    private val groups = listOf("v1.10.0", "1", "10", "0", null)

    private fun fill(template: String) = MatchTemplate.parse(template)!!.fill(groups)

    @Test
    fun aNumberAloneOrAfterADollarNamesOneGroup() {
        assertEquals("10", fill("2"))
        assertEquals("10", fill("$2"))
        assertEquals("v1.10.0", fill("0"))
        assertEquals("1", fill(" \$1 "))
    }

    @Test
    fun aTemplateJoinsSeveralGroups() {
        assertEquals("1.10", fill("$1.$2"))
        assertEquals("v1-10-0", fill("v$1-$2-$3"))
        assertEquals("1.10.", fill("$1.$2.$4"))
    }

    @Test
    fun aBackslashKeepsTheDollarSign() {
        assertEquals("$1-10", fill("\\$1-$2"))
        assertEquals("costs $10", fill("costs \\$$2"))
    }

    @Test
    fun aGroupTheMatchLacksGivesNothing() {
        assertNull(fill("$5"))
        assertNull(fill("$1.$5"))
        assertNull(fill("99999999999"))
        assertTrue(MatchTemplate.parse("$9")!!.isValid)
    }

    @Test
    fun aGroupThatTookNoPartIsEmpty() {
        assertNull(fill("$4"))
        assertEquals("1.", fill("$1.$4"))
    }

    @Test
    fun aTemplateThatNamesNoGroupGivesNothing() {
        val none = MatchTemplate.parse("version")!!
        assertFalse(none.isValid)
        assertNull(none.fill(groups))
        assertFalse(MatchTemplate.parse("$")!!.isValid)
        assertNull(MatchTemplate.parse("  "))
        assertNull(MatchTemplate.parse(null))
    }

    @Test
    fun aPatternFillsTheTemplateFromItsFirstMatch() {
        val pattern = SafePattern.compile("(\\d+)\\.(\\d+)")
        assertEquals("2-1", pattern.extract("app 1.2 and 3.4", MatchTemplate.parse("$2-$1")!!))
        assertNull(pattern.extract("no numbers", MatchTemplate.parse("$1")!!))
        assertEquals(listOf("1.2", "1", "2"), pattern.groups("app 1.2"))
        assertEquals(listOf("1", "1", null), SafePattern.compile("(\\d)(x)?").groups("1"))
    }
}
