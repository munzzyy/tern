package io.github.munzzyy.tern.ui.theme

import androidx.compose.ui.graphics.toArgb
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.ColorStyle
import io.github.munzzyy.tern.engine.Contrast
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.engine.Settings
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StylesTest {
    private val seeds: List<Pair<String, Seed>> =
        Palette.entries.map { it.name to it.seed } +
            (0 until 360 step 15).flatMap { hue -> listOf(0.0, 0.5, 1.0).map { "hue $hue strength $it" to Seed(hue, it) } }

    private fun least(role: Role, contrast: Contrast): Double = when {
        role == Role.OUTLINE -> if (contrast == Contrast.HIGH) 4.5 else 3.0
        contrast == Contrast.HIGH -> 7.0
        else -> 4.5
    }

    @Test
    fun everyStyleStaysReadableEverywhere() {
        val faint = mutableListOf<String>()
        for (style in ColorStyle.entries) {
            for ((name, seed) in seeds) {
                for (dark in listOf(false, true)) {
                    for (black in listOf(false, true)) {
                        for (contrast in Contrast.entries) {
                            val roles = roles(seed.hue, seed.strength, dark, contrast, black, style)
                            for (role in Role.entries) {
                                for (ground in role.readOn) {
                                    val ratio = wcag(roles[role], roles[ground])
                                    if (ratio < least(role, contrast)) faint += "$style $name dark $dark black $black $contrast: $role on $ground is %.2f".format(ratio)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("${faint.size} pairs are too faint:\n" + faint.take(12).joinToString("\n"), faint.isEmpty())
    }

    @Test
    fun vibrantIsMoreColourfulAndExpressiveTurnsTheOtherColours() {
        for ((name, seed) in seeds.filter { it.second.strength > 0.3 }) {
            val standard = roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD)
            val vibrant = roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD, style = ColorStyle.VIBRANT)
            val expressive = roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD, style = ColorStyle.EXPRESSIVE)
            assertTrue("$name surface", oklchOf(vibrant[Role.SURFACE_CONTAINER]).chroma > oklchOf(standard[Role.SURFACE_CONTAINER]).chroma)
            assertTrue("$name secondary", oklchOf(vibrant[Role.SECONDARY]).chroma >= oklchOf(standard[Role.SECONDARY]).chroma)
            val turned = abs(((oklchOf(expressive[Role.TERTIARY]).hue - seed.hue + 540) % 360) - 180)
            assertTrue("$name tertiary turned by %.0f".format(turned), turned > 80)
            // The colours of meaning never follow the style.
            for (role in listOf(Role.VERIFIED, Role.CAUTION, Role.ERROR)) assertEquals("$name $role", standard[role], expressive[role])
        }
    }

    @Test
    fun theStyleAndTheOwnStrengthReachTheScheme() {
        val own = Settings(colorSource = ColorSource.CUSTOM, customHue = 200, customStrength = 30, colorStyle = ColorStyle.VIBRANT)
        assertEquals(Seed(200, 0.3, ColorStyle.VIBRANT), seedOf(own))
        assertEquals(Palette.MOSS.seed.copy(style = ColorStyle.VIBRANT), seedOf(own.copy(colorSource = ColorSource.PALETTE, palette = Palette.MOSS)))
        val scheme = ternColors(own, dark = false).scheme
        assertEquals(roles(200, 0.3, false, Contrast.STANDARD, style = ColorStyle.VIBRANT)[Role.PRIMARY], scheme.primary.toArgb())
        // A primary already at the edge of what a screen shows cannot grow; the surfaces show the style.
        assertNotEquals(scheme.surfaceContainer, ternColors(own.copy(colorStyle = ColorStyle.STANDARD), dark = false).scheme.surfaceContainer)
    }

    @Test
    fun aColourCodeGivesItsHueAndStrength() {
        assertEquals(0, seedOfColor(0xFF808080.toInt()).second)
        val (redHue, redStrength) = seedOfColor(0xFFFF0000.toInt())
        assertTrue("red is at $redHue", redHue in 25..33)
        assertEquals(100, redStrength)
        val (blueHue, blueStrength) = seedOfColor(0xFF3D5A80.toInt())
        assertTrue("slate blue is at $blueHue", blueHue in 250..265)
        assertTrue("slate blue is muted, $blueStrength", blueStrength in 10..60)
    }
}
