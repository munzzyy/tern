package io.github.munzzyy.tern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings v0.1.0 wrote mean after the update what they meant before it. */
class V010SettingsTest {
    /** Every key v0.1.0's SettingsStore.save() wrote, with the type it wrote it as (git show 8399259cb464^). */
    private fun v010(hours: Int = 6, unmetered: Boolean = true, charging: Boolean = false): Map<String, Any?> = mapOf(
        "checkEveryHours" to hours,
        "onlyOnUnmetered" to unmetered,
        "onlyWhileCharging" to charging,
        "defaultUpdateMode" to "NOTIFY",
        "includePrereleasesByDefault" to false,
        "minAgeDaysByDefault" to 0,
        "notifyUpdates" to true,
        "notifyInstalled" to true,
        "notifyFailures" to false,
        "notifyNames" to true,
        "keepInstallers" to false,
        "claimUpdateOwnership" to false,
        "openObtainiumLinks" to false,
        "theme" to "SYSTEM",
        "colorSource" to "WALLPAPER",
        "palette" to "INK",
        "customHue" to 250,
        "contrast" to "STANDARD",
        "pureBlack" to false,
        "density" to "COMFORTABLE",
        "corners" to "ROUND",
        "iconShape" to "CIRCLE",
        "sourceIcons" to true,
        "proxy" to "NONE",
        "proxyHost" to "127.0.0.1",
        "proxyPort" to 9050,
    )

    @Test
    fun wifiOnlyInV010KeepsHoldingTheChecksBack() {
        val stored = v010(unmetered = true, charging = true)
        assertTrue(SettingsStore.checkLimit(stored, "checkOnlyOnUnmetered", "onlyOnUnmetered"))
        assertTrue(SettingsStore.checkLimit(stored, "checkOnlyWhileCharging", "onlyWhileCharging"))
        assertEquals(360, SettingsStore.storedMinutes(stored, 360))
        assertEquals(24 * 60, SettingsStore.storedMinutes(v010(hours = 24), 360))
        assertEquals(0, SettingsStore.storedMinutes(v010(hours = 0), 360))
    }

    @Test
    fun v010SwitchesThatWereOffStayOff() {
        val stored = v010(unmetered = false, charging = false)
        assertFalse(SettingsStore.checkLimit(stored, "checkOnlyOnUnmetered", "onlyOnUnmetered"))
        assertFalse(SettingsStore.checkLimit(stored, "checkOnlyWhileCharging", "onlyWhileCharging"))
    }

    @Test
    fun onceSavedTheCheckSwitchesStandOnTheirOwn() {
        // save() writes the new keys and takes checkEveryHours out.
        val saved = mapOf("checkEveryMinutes" to 90, "onlyOnUnmetered" to true, "checkOnlyOnUnmetered" to false, "checkOnlyWhileCharging" to true)
        assertFalse(SettingsStore.checkLimit(saved, "checkOnlyOnUnmetered", "onlyOnUnmetered"))
        assertTrue(SettingsStore.checkLimit(saved, "checkOnlyWhileCharging", "onlyWhileCharging"))
        assertEquals(90, SettingsStore.storedMinutes(saved, 360))
    }

    @Test
    fun aNewInstallChecksOnAnyNetwork() {
        assertFalse(SettingsStore.checkLimit(emptyMap<String, Any?>(), "checkOnlyOnUnmetered", "onlyOnUnmetered"))
        assertFalse(SettingsStore.checkLimit(mapOf("onlyOnUnmetered" to true), "checkOnlyOnUnmetered", "onlyOnUnmetered"))
    }
}
