package io.github.munzzyy.tern.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A five-pointed star, drawn as an outline or filled, for favourites. */
private fun star(filled: Boolean): ImageVector = glyph(if (filled) "StarFilled" else "Star") {
    val points = (0 until 10).map { i ->
        val radius = if (i % 2 == 0) 9f else 4f
        val angle = -PI / 2 + i * PI / 5
        (12f + radius * cos(angle).toFloat()) to (12.6f + radius * sin(angle).toFloat())
    }
    val draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit = {
        moveTo(points[0].first, points[0].second)
        for ((x, y) in points.drop(1)) lineTo(x, y)
        close()
    }
    if (filled) solid(draw) else line(draw)
}

private val starLine: ImageVector by lazy { star(filled = false) }
private val starFilled: ImageVector by lazy { star(filled = true) }

private val share: ImageVector by lazy {
    glyph("Share") {
        line {
            circle(17.5f, 5.5f, 2.5f)
            circle(6.5f, 12f, 2.5f)
            circle(17.5f, 18.5f, 2.5f)
            moveTo(8.7f, 10.7f)
            lineTo(15.3f, 6.8f)
            moveTo(8.7f, 13.3f)
            lineTo(15.3f, 17.2f)
        }
    }
}

private val tune: ImageVector by lazy {
    glyph("Tune") {
        line {
            moveTo(4f, 7f)
            horizontalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(4f, 17f)
            horizontalLineTo(20f)
        }
        solid {
            circle(9f, 7f, 2.2f)
            circle(15f, 12f, 2.2f)
            circle(7f, 17f, 2.2f)
        }
    }
}

val Glyphs.Star: ImageVector get() = starLine
val Glyphs.StarFilled: ImageVector get() = starFilled
val Glyphs.Share: ImageVector get() = share
val Glyphs.Tune: ImageVector get() = tune
