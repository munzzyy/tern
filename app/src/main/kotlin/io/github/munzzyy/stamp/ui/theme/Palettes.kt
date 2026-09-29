package io.github.munzzyy.stamp.ui.theme

import io.github.munzzyy.stamp.engine.ColorSource
import io.github.munzzyy.stamp.engine.Palette
import io.github.munzzyy.stamp.engine.Settings

/** Where a scheme starts: a hue in degrees and a strength from 0 for nearly grey to 1 for vivid. */
data class Seed(val hue: Int, val strength: Double)

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
    if (settings.colorSource == ColorSource.CUSTOM) Seed(settings.customHue, CUSTOM_STRENGTH) else settings.palette.seed
