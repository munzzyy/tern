package io.github.munzzyy.tern.ui.theme

import io.github.munzzyy.tern.engine.ColorStyle
import io.github.munzzyy.tern.engine.Contrast

internal enum class Ramp { PRIMARY, SECONDARY, TERTIARY, NEUTRAL, NEUTRAL_VARIANT, REFUSED, VERIFIED, CAUTION }

/** The tone a role takes at standard, medium and high contrast. */
internal class Tones(val standard: Int, val medium: Int = standard, val high: Int = standard) {
    fun at(contrast: Contrast): Int = when (contrast) {
        Contrast.STANDARD -> standard
        Contrast.MEDIUM -> medium
        Contrast.HIGH -> high
    }
}

private object Step {
    val accentLight = Tones(36, 31, 25)
    val accentDark = Tones(80, 85, 91)
    val onAccentLight = Tones(100)
    val onAccentDark = Tones(20, 14, 6)
    val containerLight = Tones(90)
    val containerDark = Tones(30)
    val onContainerLight = Tones(20, 14, 6)
    val onContainerDark = Tones(90, 93, 97)
    val fixed = Tones(90)
    val fixedDim = Tones(80)
    val onFixed = Tones(10, 8, 4)
    val onFixedVariant = Tones(30, 26, 18)
    val textLight = Tones(10, 8, 4)
    val textDark = Tones(90, 93, 97)
    val quietLight = Tones(30, 26, 18)
    val quietDark = Tones(80, 85, 91)
}

/**
 * Every colour a screen may use: Material's roles, and verified and caution, whose hues never
 * change. Refused is Material's error. Each row is a ramp and the tone on it, light and dark.
 */
enum class Role(internal val ramp: Ramp, internal val light: Tones, internal val dark: Tones, internal val black: Int? = null) {
    PRIMARY(Ramp.PRIMARY, Step.accentLight, Step.accentDark),
    ON_PRIMARY(Ramp.PRIMARY, Step.onAccentLight, Step.onAccentDark),
    PRIMARY_CONTAINER(Ramp.PRIMARY, Step.containerLight, Step.containerDark),
    ON_PRIMARY_CONTAINER(Ramp.PRIMARY, Step.onContainerLight, Step.onContainerDark),
    INVERSE_PRIMARY(Ramp.PRIMARY, Step.accentDark, Step.accentLight),
    PRIMARY_FIXED(Ramp.PRIMARY, Step.fixed, Step.fixed),
    PRIMARY_FIXED_DIM(Ramp.PRIMARY, Step.fixedDim, Step.fixedDim),
    ON_PRIMARY_FIXED(Ramp.PRIMARY, Step.onFixed, Step.onFixed),
    ON_PRIMARY_FIXED_VARIANT(Ramp.PRIMARY, Step.onFixedVariant, Step.onFixedVariant),

    SECONDARY(Ramp.SECONDARY, Step.accentLight, Step.accentDark),
    ON_SECONDARY(Ramp.SECONDARY, Step.onAccentLight, Step.onAccentDark),
    SECONDARY_CONTAINER(Ramp.SECONDARY, Step.containerLight, Step.containerDark),
    ON_SECONDARY_CONTAINER(Ramp.SECONDARY, Step.onContainerLight, Step.onContainerDark),
    SECONDARY_FIXED(Ramp.SECONDARY, Step.fixed, Step.fixed),
    SECONDARY_FIXED_DIM(Ramp.SECONDARY, Step.fixedDim, Step.fixedDim),
    ON_SECONDARY_FIXED(Ramp.SECONDARY, Step.onFixed, Step.onFixed),
    ON_SECONDARY_FIXED_VARIANT(Ramp.SECONDARY, Step.onFixedVariant, Step.onFixedVariant),

    TERTIARY(Ramp.TERTIARY, Step.accentLight, Step.accentDark),
    ON_TERTIARY(Ramp.TERTIARY, Step.onAccentLight, Step.onAccentDark),
    TERTIARY_CONTAINER(Ramp.TERTIARY, Step.containerLight, Step.containerDark),
    ON_TERTIARY_CONTAINER(Ramp.TERTIARY, Step.onContainerLight, Step.onContainerDark),
    TERTIARY_FIXED(Ramp.TERTIARY, Step.fixed, Step.fixed),
    TERTIARY_FIXED_DIM(Ramp.TERTIARY, Step.fixedDim, Step.fixedDim),
    ON_TERTIARY_FIXED(Ramp.TERTIARY, Step.onFixed, Step.onFixed),
    ON_TERTIARY_FIXED_VARIANT(Ramp.TERTIARY, Step.onFixedVariant, Step.onFixedVariant),

    ERROR(Ramp.REFUSED, Step.accentLight, Step.accentDark),
    ON_ERROR(Ramp.REFUSED, Step.onAccentLight, Step.onAccentDark),
    ERROR_CONTAINER(Ramp.REFUSED, Step.containerLight, Step.containerDark),
    ON_ERROR_CONTAINER(Ramp.REFUSED, Step.onContainerLight, Step.onContainerDark),

    VERIFIED(Ramp.VERIFIED, Step.accentLight, Step.accentDark),
    ON_VERIFIED(Ramp.VERIFIED, Step.onAccentLight, Step.onAccentDark),
    VERIFIED_CONTAINER(Ramp.VERIFIED, Step.containerLight, Step.containerDark),
    ON_VERIFIED_CONTAINER(Ramp.VERIFIED, Step.onContainerLight, Step.onContainerDark),

    CAUTION(Ramp.CAUTION, Step.accentLight, Step.accentDark),
    ON_CAUTION(Ramp.CAUTION, Step.onAccentLight, Step.onAccentDark),
    CAUTION_CONTAINER(Ramp.CAUTION, Step.containerLight, Step.containerDark),
    ON_CAUTION_CONTAINER(Ramp.CAUTION, Step.onContainerLight, Step.onContainerDark),

    BACKGROUND(Ramp.NEUTRAL, Tones(98), Tones(6), black = 0),
    ON_BACKGROUND(Ramp.NEUTRAL, Step.textLight, Step.textDark),
    SURFACE(Ramp.NEUTRAL, Tones(98), Tones(6), black = 0),
    ON_SURFACE(Ramp.NEUTRAL, Step.textLight, Step.textDark),
    SURFACE_DIM(Ramp.NEUTRAL, Tones(87), Tones(6), black = 0),
    SURFACE_BRIGHT(Ramp.NEUTRAL, Tones(98), Tones(24), black = 16),
    SURFACE_CONTAINER_LOWEST(Ramp.NEUTRAL, Tones(100), Tones(4), black = 0),
    SURFACE_CONTAINER_LOW(Ramp.NEUTRAL, Tones(96), Tones(10), black = 3),
    SURFACE_CONTAINER(Ramp.NEUTRAL, Tones(94), Tones(12), black = 5),
    SURFACE_CONTAINER_HIGH(Ramp.NEUTRAL, Tones(92), Tones(17), black = 9),
    SURFACE_CONTAINER_HIGHEST(Ramp.NEUTRAL, Tones(90), Tones(22), black = 13),
    SURFACE_VARIANT(Ramp.NEUTRAL_VARIANT, Tones(90), Tones(30), black = 22),
    ON_SURFACE_VARIANT(Ramp.NEUTRAL_VARIANT, Step.quietLight, Step.quietDark),
    OUTLINE(Ramp.NEUTRAL_VARIANT, Tones(50, 42, 32), Tones(62, 70, 80)),
    OUTLINE_VARIANT(Ramp.NEUTRAL_VARIANT, Tones(80, 72, 60), Tones(30, 38, 50)),
    INVERSE_SURFACE(Ramp.NEUTRAL, Tones(20), Tones(90)),
    INVERSE_ON_SURFACE(Ramp.NEUTRAL, Tones(95, 97, 100), Tones(20, 14, 6)),
    SCRIM(Ramp.NEUTRAL, Tones(0), Tones(0)),
    ;

    /** The backgrounds this role is read on, as text or as a glyph. Empty for a role that is only ever a background. */
    val readOn: List<Role>
        get() = when (this) {
            PRIMARY, SECONDARY, TERTIARY, ERROR, VERIFIED, CAUTION,
            ON_BACKGROUND, ON_SURFACE, ON_SURFACE_VARIANT, OUTLINE,
            -> SURFACES
            ON_PRIMARY -> listOf(PRIMARY)
            ON_PRIMARY_CONTAINER -> listOf(PRIMARY_CONTAINER)
            ON_PRIMARY_FIXED, ON_PRIMARY_FIXED_VARIANT -> listOf(PRIMARY_FIXED, PRIMARY_FIXED_DIM)
            ON_SECONDARY -> listOf(SECONDARY)
            ON_SECONDARY_CONTAINER -> listOf(SECONDARY_CONTAINER)
            ON_SECONDARY_FIXED, ON_SECONDARY_FIXED_VARIANT -> listOf(SECONDARY_FIXED, SECONDARY_FIXED_DIM)
            ON_TERTIARY -> listOf(TERTIARY)
            ON_TERTIARY_CONTAINER -> listOf(TERTIARY_CONTAINER)
            ON_TERTIARY_FIXED, ON_TERTIARY_FIXED_VARIANT -> listOf(TERTIARY_FIXED, TERTIARY_FIXED_DIM)
            ON_ERROR -> listOf(ERROR)
            ON_ERROR_CONTAINER -> listOf(ERROR_CONTAINER)
            ON_VERIFIED -> listOf(VERIFIED)
            ON_VERIFIED_CONTAINER -> listOf(VERIFIED_CONTAINER)
            ON_CAUTION -> listOf(CAUTION)
            ON_CAUTION_CONTAINER -> listOf(CAUTION_CONTAINER)
            INVERSE_ON_SURFACE, INVERSE_PRIMARY -> listOf(INVERSE_SURFACE)
            else -> emptyList()
        }

    /** The contrast the engine works towards. An outline is not text, so it may stay a step under it. */
    internal fun aim(contrast: Contrast): Double = when (contrast) {
        Contrast.STANDARD -> if (this == OUTLINE) 3.0 else 4.5
        Contrast.MEDIUM -> if (this == OUTLINE) 3.6 else 5.5
        Contrast.HIGH -> if (this == OUTLINE) 4.5 else 7.0
    }

    private companion object {
        val SURFACES: List<Role> by lazy {
            listOf(
                BACKGROUND, SURFACE, SURFACE_DIM, SURFACE_BRIGHT, SURFACE_VARIANT, SURFACE_CONTAINER_LOWEST,
                SURFACE_CONTAINER_LOW, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH, SURFACE_CONTAINER_HIGHEST,
            )
        }
    }
}

/** One colour for each [Role], as 0xAARRGGBB. */
class Roles internal constructor(private val argb: IntArray) {
    operator fun get(role: Role): Int = argb[role.ordinal]

    internal fun changed(change: (IntArray) -> Unit): Roles = Roles(argb.copyOf().also(change))

    override fun equals(other: Any?): Boolean = other is Roles && argb.contentEquals(other.argb)

    override fun hashCode(): Int = argb.contentHashCode()
}

private const val REFUSED_HUE = 27.0
private const val VERIFIED_HUE = 150.0
private const val CAUTION_HUE = 75.0
private const val TERTIARY_TURN = 45.0
private const val EXPRESSIVE_TERTIARY_TURN = 120.0
private const val EXPRESSIVE_SECONDARY_TURN = 330.0
private const val DARK_CHROMA = 0.8

private fun Ramp.hue(seed: Double, style: ColorStyle): Double = when (this) {
    Ramp.SECONDARY -> if (style == ColorStyle.EXPRESSIVE) (seed + EXPRESSIVE_SECONDARY_TURN) % 360 else seed
    Ramp.TERTIARY -> (seed + if (style == ColorStyle.EXPRESSIVE) EXPRESSIVE_TERTIARY_TURN else TERTIARY_TURN) % 360
    Ramp.REFUSED -> REFUSED_HUE
    Ramp.VERIFIED -> VERIFIED_HUE
    Ramp.CAUTION -> CAUTION_HUE
    else -> seed
}

/** The colours of meaning keep their chroma in every style; the accents and the surfaces take the style's. */
private fun Ramp.chroma(strength: Double, dark: Boolean, style: ColorStyle): Double {
    val accent = if (dark) DARK_CHROMA else 1.0
    val lift = when (style) {
        ColorStyle.STANDARD -> 1.0
        ColorStyle.VIBRANT -> 1.4
        ColorStyle.EXPRESSIVE -> 1.2
    }
    val tint = when (style) {
        ColorStyle.STANDARD -> 1.0
        ColorStyle.VIBRANT -> 1.75
        ColorStyle.EXPRESSIVE -> 1.5
    }
    return when (this) {
        Ramp.PRIMARY -> (0.04 + 0.15 * strength) * accent * lift
        Ramp.SECONDARY -> (0.015 + 0.045 * strength) * accent * lift
        Ramp.TERTIARY -> (0.03 + 0.09 * strength) * accent * lift
        Ramp.NEUTRAL -> (0.004 + 0.008 * strength) * tint
        Ramp.NEUTRAL_VARIANT -> (0.008 + 0.016 * strength) * tint
        Ramp.REFUSED -> 0.17 * accent
        Ramp.VERIFIED, Ramp.CAUTION -> 0.14 * accent
    }
}

/**
 * Every colour of a scheme from one hue, in degrees, and one strength, from 0 for nearly grey to
 * 1 for vivid. [pureBlack] applies to a dark scheme only.
 */
fun roles(hue: Int, strength: Double, dark: Boolean, contrast: Contrast, pureBlack: Boolean = false, style: ColorStyle = ColorStyle.STANDARD): Roles {
    val table = drawn(hue, strength, dark, contrast, style)
    return (if (dark && pureBlack) table.blackened() else table).fitted(contrast)
}

/** The scheme as the table of tones gives it, before any pair is measured. */
internal fun drawn(hue: Int, strength: Double, dark: Boolean, contrast: Contrast, style: ColorStyle = ColorStyle.STANDARD): Roles {
    return Roles(IntArray(Role.entries.size) { tableColor(Role.entries[it], hue, strength, dark, contrast, style) })
}

/** One role straight from the table of tones: enough for a swatch, without working out the scheme around it. */
fun tableColor(role: Role, hue: Int, strength: Double, dark: Boolean, contrast: Contrast, style: ColorStyle = ColorStyle.STANDARD): Int {
    val seed = ((hue % 360) + 360) % 360.0
    val tone = (if (dark) role.dark else role.light).at(contrast)
    return toned(role.ramp.hue(seed, style), role.ramp.chroma(strength.coerceIn(0.0, 1.0), dark, style), tone.toDouble())
}

/** Pure black: the backgrounds of a dark scheme go to black and the surfaces on them to the tones next to it. */
fun Roles.blackened(): Roles = changed { argb ->
    for (role in Role.entries) {
        val tone = role.black ?: continue
        val from = oklchOf(argb[role.ordinal])
        argb[role.ordinal] = toned(from.hue, from.chroma, tone.toDouble())
    }
}

private val CARRIERS: List<Role> by lazy { Role.entries.flatMap { it.readOn }.distinct() }
private val GROUNDS: List<Role> by lazy { CARRIERS.filter { it.readOn.isEmpty() } }
private val ACCENTS: List<Role> by lazy { CARRIERS.filter { it.readOn.isNotEmpty() } }
private val READ_ONLY: List<Role> by lazy { Role.entries.filter { it !in CARRIERS && it.readOn.isNotEmpty() } }

/**
 * Moves what is read away from what it is read on until every pair reaches the contrast asked
 * for. A background moves only when neither black nor white could be read on it. A scheme made
 * by [roles] barely moves; one that Android made from a wallpaper is held to the same promise
 * this way.
 */
fun Roles.fitted(contrast: Contrast): Roles = changed { argb ->
    fun carry(role: Role) {
        val aim = Role.entries.filter { role in it.readOn }.maxOf { it.aim(contrast) }
        argb[role.ordinal] = ableToCarry(argb[role.ordinal], aim)
    }

    fun part(role: Role) {
        argb[role.ordinal] = apart(argb[role.ordinal], role.readOn.map { argb[it.ordinal] }, role.aim(contrast))
    }
    GROUNDS.forEach(::carry)
    for (accent in ACCENTS) {
        part(accent)
        carry(accent)
    }
    READ_ONLY.forEach(::part)
}

private fun reaches(color: Int, grounds: List<Int>, aim: Double): Boolean = grounds.all { contrast(color, it) >= aim }

/** [color] at the nearest tone that reaches [aim] against every one of [grounds], on the side of them it is already on if that can be done. */
private fun apart(color: Int, grounds: List<Int>, aim: Double): Int {
    if (reaches(color, grounds, aim)) return color
    val lighter = luminance(color) >= grounds.map(::luminance).average()
    return moved(color, grounds, aim, lighter) ?: moved(color, grounds, aim, !lighter) ?: run {
        val ends = listOf(BLACK, WHITE)
        ends.maxBy { end -> grounds.minOf { contrast(end, it) } }
    }
}

private fun moved(color: Int, grounds: List<Int>, aim: Double, lighter: Boolean): Int? {
    val from = oklchOf(color)
    var tone = if (lighter) {
        grounds.maxOf { toneOfLuminance(aim * (luminance(it) + 0.05) - 0.05) }
    } else {
        grounds.minOf { toneOfLuminance((luminance(it) + 0.05) / aim - 0.05) }
    }
    while (tone in 0.0..100.0) {
        val candidate = toned(from.hue, from.chroma, tone)
        if (reaches(candidate, grounds, aim)) return candidate
        tone += if (lighter) TONE_STEP else -TONE_STEP
    }
    val end = if (lighter) WHITE else BLACK
    return end.takeIf { reaches(it, grounds, aim) }
}

/** A background in the middle tones carries neither black nor white text well, so it is moved out of them. */
private fun ableToCarry(ground: Int, aim: Double): Int {
    if (contrast(ground, WHITE) >= aim || contrast(ground, BLACK) >= aim) return ground
    val from = oklchOf(ground)
    val lighter = toneOf(ground) >= 50
    var tone = toneOfLuminance(if (lighter) aim * 0.05 - 0.05 else 1.05 / aim - 0.05)
    while (tone in 0.0..100.0) {
        val candidate = toned(from.hue, from.chroma, tone)
        if (contrast(candidate, if (lighter) BLACK else WHITE) >= aim) return candidate
        tone += if (lighter) TONE_STEP else -TONE_STEP
    }
    return if (lighter) WHITE else BLACK
}

private const val TONE_STEP = 0.5
