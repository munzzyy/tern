package io.github.munzzyy.tern.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/** Material 3's type scale: size in sp on a phone, weight, and letter spacing in sp. Screen titles are a step heavier than titles inside a screen. */
enum class TextRole(val size: Int, val weight: Int, val spacing: Double) {
    DISPLAY_LARGE(57, 400, -0.25),
    DISPLAY_MEDIUM(45, 400, 0.0),
    DISPLAY_SMALL(36, 400, 0.0),
    HEADLINE_LARGE(32, 600, 0.0),
    HEADLINE_MEDIUM(28, 600, 0.0),
    HEADLINE_SMALL(24, 600, 0.0),
    TITLE_LARGE(22, 600, 0.0),
    TITLE_MEDIUM(16, 500, 0.15),
    TITLE_SMALL(14, 500, 0.1),
    BODY_LARGE(16, 400, 0.5),
    BODY_MEDIUM(14, 400, 0.25),
    BODY_SMALL(12, 400, 0.4),
    LABEL_LARGE(14, 500, 0.1),
    LABEL_MEDIUM(12, 500, 0.5),
    LABEL_SMALL(11, 500, 0.5),
}

private val SIZES = listOf(11, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 45, 57, 64)
private val LINE_HEIGHTS = listOf(16, 16, 20, 24, 26, 28, 28, 32, 36, 40, 44, 52, 64, 72)

/** On a television every text takes the next size up. */
fun textSize(role: TextRole, television: Boolean): Int = SIZES[SIZES.indexOf(role.size) + if (television) 1 else 0]

fun lineHeight(size: Int): Int = LINE_HEIGHTS[SIZES.indexOf(size)]

private fun style(role: TextRole, television: Boolean): TextStyle {
    val size = textSize(role, television)
    return TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight(role.weight),
        fontSize = size.sp,
        lineHeight = lineHeight(size).sp,
        letterSpacing = role.spacing.sp,
    )
}

fun typographyFor(television: Boolean): Typography = Typography(
    displayLarge = style(TextRole.DISPLAY_LARGE, television),
    displayMedium = style(TextRole.DISPLAY_MEDIUM, television),
    displaySmall = style(TextRole.DISPLAY_SMALL, television),
    headlineLarge = style(TextRole.HEADLINE_LARGE, television),
    headlineMedium = style(TextRole.HEADLINE_MEDIUM, television),
    headlineSmall = style(TextRole.HEADLINE_SMALL, television),
    titleLarge = style(TextRole.TITLE_LARGE, television),
    titleMedium = style(TextRole.TITLE_MEDIUM, television),
    titleSmall = style(TextRole.TITLE_SMALL, television),
    bodyLarge = style(TextRole.BODY_LARGE, television),
    bodyMedium = style(TextRole.BODY_MEDIUM, television),
    bodySmall = style(TextRole.BODY_SMALL, television),
    labelLarge = style(TextRole.LABEL_LARGE, television),
    labelMedium = style(TextRole.LABEL_MEDIUM, television),
    labelSmall = style(TextRole.LABEL_SMALL, television),
)

/** One step heavier, for the name of an app. */
fun TextStyle.heavier(): TextStyle = copy(fontWeight = FontWeight(((fontWeight?.weight ?: 400) + 100).coerceAtMost(900)))

/** Digits of one width, so versions and sizes line up under each other. */
fun TextStyle.figures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** For fingerprints and hashes: one width for every character, and left to right in every language. */
fun TextStyle.fingerprint(): TextStyle = copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr, letterSpacing = 0.sp)
