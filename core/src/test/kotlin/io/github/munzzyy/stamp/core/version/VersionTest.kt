package io.github.munzzyy.stamp.core.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun readsNumbersOutOfRealTags() {
        val cases = mapOf(
            "1.2.3" to listOf(1L, 2L, 3L),
            "v1.2.3" to listOf(1L, 2L, 3L),
            "V2.0" to listOf(2L, 0L),
            "release-1.2" to listOf(1L, 2L),
            "app-v3.4.5-fdroid" to listOf(3L, 4L, 5L),
            "1.2.3.4" to listOf(1L, 2L, 3L, 4L),
            "2024.10.01" to listOf(2024L, 10L, 1L),
            "2024-05-01" to listOf(2024L, 5L, 1L),
            "20240101" to listOf(20240101L),
            "1.0.0+build5" to listOf(1L, 0L, 0L),
            "1.2.3 (45)" to listOf(1L, 2L, 3L),
            "7.1.2-16-gabcdef0" to listOf(7L, 1L, 2L, 16L),
            "h264-1.0" to listOf(1L, 0L),
            "arm64-v8a-2.5" to listOf(2L, 5L),
            "1_2_3" to listOf(1L, 2L, 3L),
        )
        for ((text, numbers) in cases) assertEquals(text, numbers, Version.parse(text).numbers)
    }

    @Test
    fun readsStages() {
        assertEquals(Stage.BETA, Version.parse("1.2.3-beta.1").stage)
        assertEquals(1L, Version.parse("1.2.3-beta.1").stageNumber)
        assertEquals(Stage.RC, Version.parse("1.0rc1").stage)
        assertEquals(Stage.RC, Version.parse("2.0.0-pre").stage)
        assertEquals(Stage.ALPHA, Version.parse("3.0a2").stage)
        assertEquals(2L, Version.parse("3.0a2").stageNumber)
        assertEquals(Stage.DEV, Version.parse("nightly-2024-05-01").stage)
        assertEquals(Stage.DEV, Version.parse("1.4.0-SNAPSHOT").stage)
        assertEquals(Stage.RELEASE, Version.parse("1.2.3-fdroid").stage)
        assertEquals(Stage.RELEASE, Version.parse("1.2.3-release").stage)
        assertEquals(Stage.RELEASE, Version.parse("v4.1-stable").stage)
        assertTrue(Version.parse("1.0-beta").isPrerelease)
        assertFalse(Version.parse("1.0").isPrerelease)
    }

    @Test
    fun ordersTheWayPeopleExpect() {
        val ascending = listOf(
            "1.0.0-dev", "1.0.0-alpha", "1.0.0-alpha.2", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.0-3-gdeadbeef", "1.0.1", "1.9", "1.10", "1.10.1", "2.0", "2024.1.1",
        )
        for (i in ascending.indices) {
            for (j in ascending.indices) {
                val expected = Integer.signum(i.compareTo(j))
                val actual = Integer.signum(Version.compare(ascending[i], ascending[j]))
                assertEquals("${ascending[i]} vs ${ascending[j]}", expected, actual)
            }
        }
    }

    @Test
    fun treatsDecoratedTagsAsTheSameRelease() {
        assertTrue(Version.same("v1.2.3", "1.2.3"))
        assertTrue(Version.same("1.2.3-fdroid", "1.2.3"))
        assertTrue(Version.same("app-v1.2.3", "1.2.3+42"))
        assertTrue(Version.same("1.2", "1.2.0"))
        assertTrue(Version.same("1.0-rc1", "1.0.0-RC.1"))
        assertFalse(Version.same("1.2.3", "1.2.4"))
        assertFalse(Version.same("1.2.3-beta", "1.2.3"))
        assertFalse(Version.same("1.2.3-beta.1", "1.2.3-beta.2"))
    }

    @Test
    fun survivesNonsense() {
        val junk = listOf("", " ", "latest", "v", "-", "....", "9".repeat(40), "1." + "2.".repeat(50) + "3", "x".repeat(5000), "1.2.3-" + "beta".repeat(100))
        for (text in junk) {
            val v = Version.parse(text)
            assertEquals(0, v.compareTo(Version.parse(text)))
        }
        assertFalse(Version.parse("latest").isComparable)
        assertFalse(Version.parse("9".repeat(40)).isComparable)
        assertTrue(Version.same("latest", "LATEST"))
        assertFalse(Version.same("latest", "stable"))
        assertTrue(Version.parse("1.0") > Version.parse("latest"))
    }
}
