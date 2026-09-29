package io.github.munzzyy.stamp.ui.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

const val BLACK: Int = 0xFF000000.toInt()
const val WHITE: Int = 0xFFFFFFFF.toInt()

/**
 * A colour in the OKLab space: lightness from 0 to 1, chroma from 0 to about 0.4, hue in degrees.
 * Colours travel as 0xAARRGGBB, so none of the maths needs Android.
 */
data class Oklch(val lightness: Double, val chroma: Double, val hue: Double)

private class Linear(val r: Double, val g: Double, val b: Double) {
    val fits: Boolean get() = r in GAMUT && g in GAMUT && b in GAMUT

    val luminance: Double get() = 0.2126 * r.coerceIn(0.0, 1.0) + 0.7152 * g.coerceIn(0.0, 1.0) + 0.0722 * b.coerceIn(0.0, 1.0)

    fun toArgb(): Int = BLACK or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private companion object {
        val GAMUT = -0.0001..1.0001
    }
}

private fun channel(linear: Double): Int {
    val c = linear.coerceIn(0.0, 1.0)
    val encoded = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    return (encoded * 255).roundToInt().coerceIn(0, 255)
}

private fun linear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun linearOf(lightness: Double, chroma: Double, hue: Double): Linear {
    val angle = Math.toRadians(hue)
    val a = chroma * cos(angle)
    val b = chroma * sin(angle)
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return Linear(
        r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )
}

/** The nearest colour a screen can show: the chroma is lowered until it fits, the hue and the lightness stay. */
private fun fitted(lightness: Double, chroma: Double, hue: Double): Linear {
    val asked = linearOf(lightness, chroma, hue)
    if (asked.fits) return asked
    var low = 0.0
    var high = chroma
    repeat(CHROMA_STEPS) {
        val middle = (low + high) / 2
        if (linearOf(lightness, middle, hue).fits) low = middle else high = middle
    }
    return linearOf(lightness, low, hue)
}

fun Oklch.toArgb(): Int = fitted(lightness.coerceIn(0.0, 1.0), chroma.coerceAtLeast(0.0), hue).toArgb()

fun oklchOf(argb: Int): Oklch {
    val r = linear(argb shr 16 and 0xFF)
    val g = linear(argb shr 8 and 0xFF)
    val b = linear(argb and 0xFF)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    val hue = (Math.toDegrees(atan2(bb, a)) + 360) % 360
    return Oklch(lightness, hypot(a, bb), hue)
}

/** Relative luminance as WCAG defines it, from 0 for black to 1 for white. */
fun luminance(argb: Int): Double =
    0.2126 * linear(argb shr 16 and 0xFF) + 0.7152 * linear(argb shr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)

/** Contrast ratio as WCAG defines it, from 1 to 21. */
fun contrast(a: Int, b: Int): Double {
    val first = luminance(a)
    val second = luminance(b)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}

/** Tone is lightness as CIE L*, from 0 for black to 100 for white. */
fun luminanceOfTone(tone: Double): Double = if (tone > 8) ((tone + 16) / 116).pow(3) else tone / 903.3

fun toneOfLuminance(luminance: Double): Double = if (luminance > 0.008856) 116 * cbrt(luminance) - 16 else 903.3 * luminance

fun toneOf(argb: Int): Double = toneOfLuminance(luminance(argb))

/**
 * One step of a tonal ramp: the colour of [hue] with at most [chroma] whose luminance is that of
 * [tone]. Two tones a fixed distance apart have the same contrast whatever their hue, which is
 * what lets one table of tones serve every palette.
 */
fun toned(hue: Double, chroma: Double, tone: Double): Int {
    if (tone <= 0) return BLACK
    if (tone >= 100) return WHITE
    val wanted = luminanceOfTone(tone)
    var low = 0.0
    var high = 1.0
    repeat(LIGHTNESS_STEPS) {
        val middle = (low + high) / 2
        if (fitted(middle, chroma, hue).luminance < wanted) low = middle else high = middle
    }
    return fitted((low + high) / 2, chroma, hue).toArgb()
}

private const val CHROMA_STEPS = 16
private const val LIGHTNESS_STEPS = 20
