package io.github.munzzyy.tern.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.Contrast
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.engine.Settings

/** One meaning in four colours: [color] is read on a surface, [onColor] on [color], [onContainer] on [container]. */
@Immutable
data class StatusColor(val color: Color, val onColor: Color, val container: Color, val onContainer: Color)

/** Colours that mean the same whatever the accent is. Never the only sign of a status: a glyph and a word go with them. */
@Immutable
data class StatusColors(
    /** Green: the file passed its checks. */
    val verified: StatusColor,
    /** Amber: something waits for the user, or deserves a second look. */
    val caution: StatusColor,
    /** Red: refused or failed. The same colours as Material's error. */
    val refused: StatusColor,
)

/** A scheme and the colours of meaning that were measured together with it. */
@Immutable
data class TernColors(val scheme: ColorScheme, val status: StatusColors)

val LocalStatusColors = staticCompositionLocalOf {
    val ink = Palette.INK.seed
    roles(ink.hue, ink.strength, dark = false, Contrast.STANDARD).toStatusColors()
}

val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Material's scheme for a hue in degrees and a strength from 0 to 1. */
fun colorScheme(hue: Int, strength: Double, dark: Boolean, contrast: Contrast, pureBlack: Boolean = false): ColorScheme =
    roles(hue, strength, dark, contrast, pureBlack).toColorScheme()

/**
 * The colours the settings ask for. [wallpaper] is Android's own scheme where the settings want
 * it and the system has one; it is measured and pulled apart like any other.
 */
fun ternColors(settings: Settings, dark: Boolean, wallpaper: ColorScheme? = null): TernColors {
    val seed = seedOf(settings)
    val own = roles(seed.hue, seed.strength, dark, settings.contrast, settings.pureBlack)
    if (settings.colorSource != ColorSource.WALLPAPER || wallpaper == null) return TernColors(own.toColorScheme(), own.toStatusColors())
    val taken = wallpaper.toRoles(own)
    val roles = (if (dark && settings.pureBlack) taken.blackened() else taken).fitted(settings.contrast)
    return TernColors(roles.toColorScheme(), roles.toStatusColors())
}

private fun Roles.color(role: Role): Color = Color(this[role])

fun Roles.toStatusColors(): StatusColors = StatusColors(
    verified = StatusColor(color(Role.VERIFIED), color(Role.ON_VERIFIED), color(Role.VERIFIED_CONTAINER), color(Role.ON_VERIFIED_CONTAINER)),
    caution = StatusColor(color(Role.CAUTION), color(Role.ON_CAUTION), color(Role.CAUTION_CONTAINER), color(Role.ON_CAUTION_CONTAINER)),
    refused = StatusColor(color(Role.ERROR), color(Role.ON_ERROR), color(Role.ERROR_CONTAINER), color(Role.ON_ERROR_CONTAINER)),
)

fun Roles.toColorScheme(): ColorScheme = ColorScheme(
    primary = color(Role.PRIMARY),
    onPrimary = color(Role.ON_PRIMARY),
    primaryContainer = color(Role.PRIMARY_CONTAINER),
    onPrimaryContainer = color(Role.ON_PRIMARY_CONTAINER),
    inversePrimary = color(Role.INVERSE_PRIMARY),
    secondary = color(Role.SECONDARY),
    onSecondary = color(Role.ON_SECONDARY),
    secondaryContainer = color(Role.SECONDARY_CONTAINER),
    onSecondaryContainer = color(Role.ON_SECONDARY_CONTAINER),
    tertiary = color(Role.TERTIARY),
    onTertiary = color(Role.ON_TERTIARY),
    tertiaryContainer = color(Role.TERTIARY_CONTAINER),
    onTertiaryContainer = color(Role.ON_TERTIARY_CONTAINER),
    background = color(Role.BACKGROUND),
    onBackground = color(Role.ON_BACKGROUND),
    surface = color(Role.SURFACE),
    onSurface = color(Role.ON_SURFACE),
    surfaceVariant = color(Role.SURFACE_VARIANT),
    onSurfaceVariant = color(Role.ON_SURFACE_VARIANT),
    surfaceTint = color(Role.PRIMARY),
    inverseSurface = color(Role.INVERSE_SURFACE),
    inverseOnSurface = color(Role.INVERSE_ON_SURFACE),
    error = color(Role.ERROR),
    onError = color(Role.ON_ERROR),
    errorContainer = color(Role.ERROR_CONTAINER),
    onErrorContainer = color(Role.ON_ERROR_CONTAINER),
    outline = color(Role.OUTLINE),
    outlineVariant = color(Role.OUTLINE_VARIANT),
    scrim = color(Role.SCRIM),
    surfaceBright = color(Role.SURFACE_BRIGHT),
    surfaceDim = color(Role.SURFACE_DIM),
    surfaceContainer = color(Role.SURFACE_CONTAINER),
    surfaceContainerHigh = color(Role.SURFACE_CONTAINER_HIGH),
    surfaceContainerHighest = color(Role.SURFACE_CONTAINER_HIGHEST),
    surfaceContainerLow = color(Role.SURFACE_CONTAINER_LOW),
    surfaceContainerLowest = color(Role.SURFACE_CONTAINER_LOWEST),
    primaryFixed = color(Role.PRIMARY_FIXED),
    primaryFixedDim = color(Role.PRIMARY_FIXED_DIM),
    onPrimaryFixed = color(Role.ON_PRIMARY_FIXED),
    onPrimaryFixedVariant = color(Role.ON_PRIMARY_FIXED_VARIANT),
    secondaryFixed = color(Role.SECONDARY_FIXED),
    secondaryFixedDim = color(Role.SECONDARY_FIXED_DIM),
    onSecondaryFixed = color(Role.ON_SECONDARY_FIXED),
    onSecondaryFixedVariant = color(Role.ON_SECONDARY_FIXED_VARIANT),
    tertiaryFixed = color(Role.TERTIARY_FIXED),
    tertiaryFixedDim = color(Role.TERTIARY_FIXED_DIM),
    onTertiaryFixed = color(Role.ON_TERTIARY_FIXED),
    onTertiaryFixedVariant = color(Role.ON_TERTIARY_FIXED_VARIANT),
)

/** Material's roles from [this], verified and caution from [own], which Android's schemes do not have. */
fun ColorScheme.toRoles(own: Roles): Roles = own.changed { argb ->
    fun take(role: Role, color: Color) {
        argb[role.ordinal] = color.toArgb() or BLACK
    }
    take(Role.PRIMARY, primary)
    take(Role.ON_PRIMARY, onPrimary)
    take(Role.PRIMARY_CONTAINER, primaryContainer)
    take(Role.ON_PRIMARY_CONTAINER, onPrimaryContainer)
    take(Role.INVERSE_PRIMARY, inversePrimary)
    take(Role.SECONDARY, secondary)
    take(Role.ON_SECONDARY, onSecondary)
    take(Role.SECONDARY_CONTAINER, secondaryContainer)
    take(Role.ON_SECONDARY_CONTAINER, onSecondaryContainer)
    take(Role.TERTIARY, tertiary)
    take(Role.ON_TERTIARY, onTertiary)
    take(Role.TERTIARY_CONTAINER, tertiaryContainer)
    take(Role.ON_TERTIARY_CONTAINER, onTertiaryContainer)
    take(Role.BACKGROUND, background)
    take(Role.ON_BACKGROUND, onBackground)
    take(Role.SURFACE, surface)
    take(Role.ON_SURFACE, onSurface)
    take(Role.SURFACE_VARIANT, surfaceVariant)
    take(Role.ON_SURFACE_VARIANT, onSurfaceVariant)
    take(Role.INVERSE_SURFACE, inverseSurface)
    take(Role.INVERSE_ON_SURFACE, inverseOnSurface)
    take(Role.ERROR, error)
    take(Role.ON_ERROR, onError)
    take(Role.ERROR_CONTAINER, errorContainer)
    take(Role.ON_ERROR_CONTAINER, onErrorContainer)
    take(Role.OUTLINE, outline)
    take(Role.OUTLINE_VARIANT, outlineVariant)
    take(Role.SCRIM, scrim)
    take(Role.SURFACE_BRIGHT, surfaceBright)
    take(Role.SURFACE_DIM, surfaceDim)
    take(Role.SURFACE_CONTAINER, surfaceContainer)
    take(Role.SURFACE_CONTAINER_HIGH, surfaceContainerHigh)
    take(Role.SURFACE_CONTAINER_HIGHEST, surfaceContainerHighest)
    take(Role.SURFACE_CONTAINER_LOW, surfaceContainerLow)
    take(Role.SURFACE_CONTAINER_LOWEST, surfaceContainerLowest)
    take(Role.PRIMARY_FIXED, primaryFixed)
    take(Role.PRIMARY_FIXED_DIM, primaryFixedDim)
    take(Role.ON_PRIMARY_FIXED, onPrimaryFixed)
    take(Role.ON_PRIMARY_FIXED_VARIANT, onPrimaryFixedVariant)
    take(Role.SECONDARY_FIXED, secondaryFixed)
    take(Role.SECONDARY_FIXED_DIM, secondaryFixedDim)
    take(Role.ON_SECONDARY_FIXED, onSecondaryFixed)
    take(Role.ON_SECONDARY_FIXED_VARIANT, onSecondaryFixedVariant)
    take(Role.TERTIARY_FIXED, tertiaryFixed)
    take(Role.TERTIARY_FIXED_DIM, tertiaryFixedDim)
    take(Role.ON_TERTIARY_FIXED, onTertiaryFixed)
    take(Role.ON_TERTIARY_FIXED_VARIANT, onTertiaryFixedVariant)
}
