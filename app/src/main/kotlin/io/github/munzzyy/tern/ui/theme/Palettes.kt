package io.github.munzzyy.tern.ui.theme

import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.ColorStyle
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.engine.Settings
import kotlin.math.roundToInt

/** Where a scheme starts: a hue in degrees, a strength from 0 for nearly grey to 1 for vivid, and how far the scheme reaches from it. */
data class Seed(val hue: Int, val strength: Double, val style: ColorStyle = ColorStyle.STANDARD)

val Palette.seed: Seed
    get() = when (this) {
        Palette.INK -> Seed(277, 1.0)
        Palette.SLATE -> Seed(250, 0.3)
        Palette.TIDE -> Seed(205, 0.7)
        Palette.MOSS -> Seed(140, 0.6)
        Palette.AMBER -> Seed(75, 0.9)
        Palette.CLAY -> Seed(42, 0.7)
        Palette.ROSE -> Seed(8, 0.8)
        Palette.PLUM -> Seed(330, 0.65)
    }

const val CUSTOM_STRENGTH = 0.8

/** The seed the settings ask for. Wallpaper colours are Android's own; this is what stands in where it has none. */
fun seedOf(settings: Settings): Seed =
    if (settings.colorSource == ColorSource.CUSTOM) {
        Seed(settings.customHue, settings.customStrength.coerceIn(0, 100) / 100.0, settings.colorStyle)
    } else {
        settings.palette.seed.copy(style = settings.colorStyle)
    }

/**
 * The hue and strength of the colour [argb], so that a colour code the user typed gives the scheme
 * of that colour. The tones stay the table's, so every pair can still be read.
 */
fun seedOfColor(argb: Int): Pair<Int, Int> {
    val color = oklchOf(argb)
    val hue = (((color.hue % 360) + 360) % 360).roundToInt() % 360
    val strength = ((color.chroma - CHROMA_AT_NO_STRENGTH) / CHROMA_PER_STRENGTH).coerceIn(0.0, 1.0)
    return hue to (strength * 100).roundToInt()
}

/** The primary ramp's chroma is this at strength 0 and grows by the other for each whole strength. */
private const val CHROMA_AT_NO_STRENGTH = 0.04
private const val CHROMA_PER_STRENGTH = 0.15
