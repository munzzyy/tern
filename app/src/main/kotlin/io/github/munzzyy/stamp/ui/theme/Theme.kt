package io.github.munzzyy.stamp.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.munzzyy.stamp.engine.ColorSource
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.engine.ThemeMode
import io.github.munzzyy.stamp.ui.common.lacksTouch

/** Focus is drawn by `focusLook` and `focusHighlight`, so the ripple adds no layer of its own for it. */
private val StampRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0f, hoveredAlpha = 0.08f, pressedAlpha = 0.12f),
)

@Composable
fun isDark(settings: Settings): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Android's scheme from the wallpaper, or null where the settings do not ask for it or the system is older than Android 12. */
fun wallpaperScheme(context: Context, settings: Settings, dark: Boolean): ColorScheme? = when {
    settings.colorSource != ColorSource.WALLPAPER -> null
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
    dark -> dynamicDarkColorScheme(context)
    else -> dynamicLightColorScheme(context)
}

/**
 * Colours, shapes, type and sizes for everything inside. [television] is for a preview or a test
 * that wants the look of a device it is not running on.
 */
@Composable
fun StampTheme(settings: Settings, television: Boolean? = null, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val onTelevision = television ?: remember(configuration) { context.lacksTouch() }
    val colors = remember(settings.colorSource, settings.palette, settings.customHue, settings.contrast, settings.pureBlack, dark, configuration) {
        stampColors(settings, dark, wallpaperScheme(context, settings, dark))
    }
    val look = remember(settings.density, onTelevision) { lookFor(settings.density, onTelevision) }
    val shapes = remember(settings.corners) { shapesFor(settings.corners) }
    val outlines = remember(settings.corners, settings.iconShape) { outlinesFor(settings.corners, settings.iconShape) }
    val typography = remember(onTelevision) { typographyFor(onTelevision) }
    MaterialTheme(colorScheme = colors.scheme, shapes = shapes, typography = typography) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides StampRipple,
            LocalLook provides look,
            LocalOutlines provides outlines,
            LocalStatusColors provides colors.status,
            content = content,
        )
    }
}
