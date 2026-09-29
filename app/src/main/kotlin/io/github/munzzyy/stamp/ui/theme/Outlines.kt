package io.github.munzzyy.stamp.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.engine.Corners
import io.github.munzzyy.stamp.engine.IconShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Corner radius in dp of Material's five shape sizes, from extra small to extra large. */
fun cornerSizes(corners: Corners): List<Int> = when (corners) {
    Corners.ROUND -> listOf(8, 12, 16, 24, 32)
    Corners.SOFT -> listOf(4, 6, 8, 12, 16)
    Corners.SHARP -> listOf(2, 2, 2, 2, 2)
}

fun shapesFor(corners: Corners): Shapes {
    val (extraSmall, small, medium, large, extraLarge) = cornerSizes(corners).map { RoundedCornerShape(it.dp) }
    return Shapes(extraSmall = extraSmall, small = small, medium = medium, large = large, extraLarge = extraLarge)
}

/** The outlines Material's shape scale does not hold. Cards, chips, dialogs and sheets take theirs from `MaterialTheme.shapes`. */
@Immutable
data class Outlines(
    /** The outline of a button. Material draws every button as a pill whatever its shape scale says. */
    val button: Shape,
    /** The outline app icons are cut to in lists and headers. */
    val icon: Shape,
)

fun outlinesFor(corners: Corners, icon: IconShape): Outlines = Outlines(
    button = when (corners) {
        Corners.ROUND -> CircleShape
        Corners.SOFT -> RoundedCornerShape(10.dp)
        Corners.SHARP -> RoundedCornerShape(2.dp)
    },
    icon = when (icon) {
        IconShape.CIRCLE -> CircleShape
        IconShape.SQUIRCLE -> Squircle
        IconShape.SQUARE -> RoundedCornerShape(percent = 12)
    },
)

val LocalOutlines = staticCompositionLocalOf { outlinesFor(Corners.ROUND, IconShape.CIRCLE) }

/** A superellipse: fuller in the corners than a rounded square, flatter along the sides than a circle. */
object Squircle : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = squirclePoints(size.width, size.height)
        val path = Path()
        path.moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
        path.close()
        return Outline.Generic(path)
    }

    override fun toString(): String = "Squircle"
}

/** Points along the outline of a squircle that fills [width] by [height], x and y in turn. */
fun squirclePoints(width: Float, height: Float, count: Int = 96): FloatArray {
    val points = FloatArray(count * 2)
    val power = 2 / SQUIRCLE_EXPONENT
    for (i in 0 until count) {
        val angle = 2 * Math.PI * i / count
        val x = sign(cos(angle)) * abs(cos(angle)).pow(power)
        val y = sign(sin(angle)) * abs(sin(angle)).pow(power)
        points[i * 2] = (width / 2 * (1 + x)).toFloat()
        points[i * 2 + 1] = (height / 2 * (1 + y)).toFloat()
    }
    return points
}

private const val SQUIRCLE_EXPONENT = 4.0
