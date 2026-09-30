package io.github.munzzyy.tern.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun numbersCompareAsNumbers() {
        assertEquals(listOf("build-2", "build-9", "build-10", "build-100"), listOf("build-10", "build-9", "build-100", "build-2").sortedWith(NaturalOrder))
        assertTrue(NaturalOrder.compare("1.9.9", "1.10") < 0)
        assertEquals(0, NaturalOrder.compare("v007", "V7"))
        assertTrue(NaturalOrder.compare("app-99999999999999999999999", "app-100000000000000000000000") < 0)
    }

    @Test
    fun textComparesWithoutRegardToCase() {
        assertTrue(NaturalOrder.compare("alpha", "Beta") < 0)
        assertEquals(0, NaturalOrder.compare("RELEASE", "release"))
    }

    @Test
    fun digitsSortAfterTextAndWhatRunsOutFirstSortsFirst() {
        assertTrue(NaturalOrder.compare("v2", "2") < 0)
        assertTrue(NaturalOrder.compare("1.0", "1.0.1") < 0)
        assertTrue(NaturalOrder.compare("", "a") < 0)
        assertEquals(0, NaturalOrder.compare("", ""))
    }
}
