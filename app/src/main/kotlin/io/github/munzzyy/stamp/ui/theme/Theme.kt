package io.github.munzzyy.stamp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.munzzyy.stamp.engine.ColorSource
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.engine.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5A80),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF0F2A48),
    inversePrimary = Color(0xFFA9C7F0),
    secondary = Color(0xFF585E66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE2E8),
    onSecondaryContainer = Color(0xFF181C21),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBC),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF72777F),
    outlineVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    onPrimary = Color(0xFF0B2F55),
    primaryContainer = Color(0xFF24446A),
    onPrimaryContainer = Color(0xFFD5E3FF),
    inversePrimary = Color(0xFF3D5A80),
    secondary = Color(0xFFC3C7CE),
    onSecondary = Color(0xFF2C3137),
    secondaryContainer = Color(0xFF43474E),
    onSecondaryContainer = Color(0xFFDFE2E8),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
    surfaceContainerLowest = Color(0xFF0C0E10),
    surfaceContainerLow = Color(0xFF1A1C1F),
    surfaceContainer = Color(0xFF1E2023),
    surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333538),
)

/** Focus shows as a strong state layer, so keyboard and D-pad users can see where they are. */
private val FocusVisibleRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.3f, hoveredAlpha = 0.08f, pressedAlpha = 0.12f),
)

fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF101112),
    surfaceContainerHigh = Color(0xFF17181A),
    surfaceContainerHighest = Color(0xFF1E1F21),
)

@Composable
fun isDark(settings: Settings): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun StampTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val base = when {
        settings.colorSource == ColorSource.WALLPAPER && dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val scheme = if (dark && settings.pureBlack) base.pureBlack() else base
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalRippleConfiguration provides FocusVisibleRipple, content = content)
    }
}
