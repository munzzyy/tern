package io.github.munzzyy.tern.ui.theme

import androidx.compose.ui.graphics.toArgb
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.Contrast
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.engine.Settings
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {
    private fun apart(a: Int, b: Int): Int = abs(((a - b + 540) % 360) - 180)

    @Test
    fun everyPaletteIsARowOfHueAndStrength() {
        for (palette in Palette.entries) {
            val seed = palette.seed
            assertTrue("$palette hue ${seed.hue}", seed.hue in 0..359)
            assertTrue("$palette strength ${seed.strength}", seed.strength in 0.0..1.0)
        }
    }

    @Test
    fun noTwoPalettesCanBeMistakenForEachOther() {
        for (a in Palette.entries) {
            for (b in Palette.entries) {
                if (a.ordinal >= b.ordinal) continue
                val hues = apart(a.seed.hue, b.seed.hue)
                val strengths = abs(a.seed.strength - b.seed.strength)
                assertTrue("$a and $b are $hues degrees and $strengths apart", hues >= 25 || strengths >= 0.5)
                for (dark in listOf(false, true)) {
                    assertNotEquals(
                        roles(a.seed.hue, a.seed.strength, dark, Contrast.STANDARD)[Role.PRIMARY],
                        roles(b.seed.hue, b.seed.strength, dark, Contrast.STANDARD)[Role.PRIMARY],
                    )
                }
            }
        }
    }

    @Test
    fun inkIsTheDeepBlueVioletOfAStampPad() {
        val seed = Palette.INK.seed
        assertTrue("hue ${seed.hue}", seed.hue in 268..288)
        val ink = oklchOf(roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD)[Role.PRIMARY])
        assertTrue("deep: tone ${toneOf(ink.toArgb())}", toneOf(ink.toArgb()) < 40)
        assertTrue("strong: chroma ${ink.chroma}", ink.chroma > 0.15)
        assertEquals(Palette.INK, Settings().palette)
    }

    @Test
    fun slateIsTheQuietOne() {
        assertEquals(Palette.SLATE, Palette.entries.minBy { it.seed.strength })
    }

    @Test
    fun theSeedFollowsTheSourceOfColour() {
        val own = Settings(colorSource = ColorSource.CUSTOM, customHue = 123, palette = Palette.ROSE)
        assertEquals(Seed(123, CUSTOM_STRENGTH), seedOf(own))
        assertEquals(Palette.ROSE.seed, seedOf(own.copy(colorSource = ColorSource.PALETTE)))
        assertEquals(Palette.ROSE.seed, seedOf(own.copy(colorSource = ColorSource.WALLPAPER)))
    }

    @Test
    fun theSettingsReachTheScheme() {
        val base = Settings(colorSource = ColorSource.PALETTE, palette = Palette.MOSS)
        val moss = ternColors(base, dark = false)
        assertEquals(roles(140, Palette.MOSS.seed.strength, false, Contrast.STANDARD)[Role.PRIMARY], moss.scheme.primary.toArgb())
        assertNotEquals(moss.scheme.primary, ternColors(base.copy(palette = Palette.CLAY), dark = false).scheme.primary)
        assertNotEquals(moss.scheme.primary, ternColors(base.copy(contrast = Contrast.HIGH), dark = false).scheme.primary)
        assertNotEquals(moss.scheme.surface, ternColors(base, dark = true).scheme.surface)
        assertEquals(BLACK, ternColors(base.copy(pureBlack = true), dark = true).scheme.surface.toArgb())
        assertEquals(moss.scheme.surface, ternColors(base.copy(pureBlack = true), dark = false).scheme.surface)
        val own = ternColors(base.copy(colorSource = ColorSource.CUSTOM, customHue = 30), dark = false)
        assertEquals(roles(30, CUSTOM_STRENGTH, false, Contrast.STANDARD)[Role.PRIMARY], own.scheme.primary.toArgb())
    }

    @Test
    fun withoutAWallpaperSchemeTheWallpaperSourceFallsBackToThePalette() {
        val wallpaper = Settings(colorSource = ColorSource.WALLPAPER, palette = Palette.TIDE)
        val own = roles(205, Palette.TIDE.seed.strength, dark = false, Contrast.STANDARD)
        val fallen = ternColors(wallpaper, dark = false, wallpaper = null)
        assertEquals(own, fallen.scheme.toRoles(own))
        assertEquals(own.toStatusColors(), fallen.status)
    }

    @Test
    fun aWallpaperSchemeIsTakenAndHeldToTheSameContrast() {
        val faint = roles(40, 0.5, dark = false, Contrast.STANDARD).changed { argb ->
            argb[Role.PRIMARY.ordinal] = toned(40.0, 0.12, 62.0)
            argb[Role.ON_SURFACE_VARIANT.ordinal] = toned(40.0, 0.02, 60.0)
        }.toColorScheme()
        for (contrast in Contrast.entries) {
            val settings = Settings(colorSource = ColorSource.WALLPAPER, contrast = contrast)
            val made = ternColors(settings, dark = false, wallpaper = faint)
            val least = if (contrast == Contrast.HIGH) 7.0 else 4.5
            assertEquals("the hue of the wallpaper stays", 40.0, oklchOf(made.scheme.primary.toArgb()).hue, 6.0)
            assertTrue(wcag(made.scheme.primary.toArgb(), made.scheme.surface.toArgb()) >= least)
            assertTrue(wcag(made.scheme.primary.toArgb(), made.scheme.surfaceContainerHighest.toArgb()) >= least)
            assertTrue(wcag(made.scheme.onPrimary.toArgb(), made.scheme.primary.toArgb()) >= least)
            assertTrue(wcag(made.scheme.onSurfaceVariant.toArgb(), made.scheme.surfaceVariant.toArgb()) >= least)
            assertEquals(faint.surface, made.scheme.surface)
            assertTrue(wcag(made.status.verified.color.toArgb(), made.scheme.surface.toArgb()) >= least)
        }
        val black = ternColors(Settings(colorSource = ColorSource.WALLPAPER, pureBlack = true), dark = true, wallpaper = roles(40, 0.5, true, Contrast.STANDARD).toColorScheme())
        assertEquals(BLACK, black.scheme.surface.toArgb())
    }

    @Test
    fun theStatusColoursAreTheOnesThatWereMeasured() {
        val roles = roles(277, 1.0, dark = true, Contrast.HIGH)
        val status = roles.toStatusColors()
        assertEquals(roles[Role.VERIFIED], status.verified.color.toArgb())
        assertEquals(roles[Role.ON_VERIFIED], status.verified.onColor.toArgb())
        assertEquals(roles[Role.VERIFIED_CONTAINER], status.verified.container.toArgb())
        assertEquals(roles[Role.ON_VERIFIED_CONTAINER], status.verified.onContainer.toArgb())
        assertEquals(roles[Role.CAUTION], status.caution.color.toArgb())
        assertEquals(roles[Role.ON_CAUTION_CONTAINER], status.caution.onContainer.toArgb())
        assertEquals(roles[Role.ERROR], status.refused.color.toArgb())
        assertEquals(roles[Role.ERROR_CONTAINER], status.refused.container.toArgb())
        assertEquals(roles.toColorScheme().error, status.refused.color)
    }

    @Test
    fun aSchemeGoesToMaterialAndBackUnchanged() {
        for (dark in listOf(false, true)) {
            val roles = roles(205, 0.7, dark, Contrast.MEDIUM)
            assertEquals(roles, roles.toColorScheme().toRoles(roles))
            val other = roles(20, 0.9, dark, Contrast.MEDIUM)
            val mixed = roles.toColorScheme().toRoles(other)
            assertEquals(roles[Role.PRIMARY], mixed[Role.PRIMARY])
            assertEquals(roles[Role.ON_TERTIARY_FIXED_VARIANT], mixed[Role.ON_TERTIARY_FIXED_VARIANT])
            assertEquals(other[Role.VERIFIED], mixed[Role.VERIFIED])
        }
    }
}
