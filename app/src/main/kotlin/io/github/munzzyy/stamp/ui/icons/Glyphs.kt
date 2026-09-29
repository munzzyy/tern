package io.github.munzzyy.stamp.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun glyph(name: String, autoMirror: Boolean = false, draw: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply(draw).build()

private fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = block)

object Glyphs {
    val Apps: ImageVector by lazy {
        glyph("Apps") {
            solid {
                for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                    moveTo(x + 1.5f, y)
                    horizontalLineToRelative(4f)
                    arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                    verticalLineToRelative(4f)
                    arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                    horizontalLineToRelative(-4f)
                    arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                    verticalLineToRelative(-4f)
                    arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
                    close()
                }
            }
        }
    }

    val Activity: ImageVector by lazy {
        glyph("Activity", autoMirror = true) {
            line {
                moveTo(3f, 12f)
                horizontalLineTo(7f)
                lineTo(10f, 5f)
                lineTo(14f, 19f)
                lineTo(17f, 12f)
                horizontalLineTo(21f)
            }
        }
    }

    val Sort: ImageVector by lazy {
        glyph("Sort", autoMirror = true) {
            line {
                moveTo(4f, 7f)
                horizontalLineTo(20f)
                moveTo(4f, 12f)
                horizontalLineTo(15f)
                moveTo(4f, 17f)
                horizontalLineTo(10f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        glyph("Copy") {
            line {
                moveTo(9f, 3f)
                horizontalLineTo(19f)
                verticalLineTo(15f)
                horizontalLineTo(9f)
                close()
                moveTo(5f, 8f)
                verticalLineTo(21f)
                horizontalLineTo(15f)
            }
        }
    }

    val OpenInNew: ImageVector by lazy {
        glyph("OpenInNew", autoMirror = true) {
            line {
                moveTo(11f, 5f)
                horizontalLineTo(5f)
                verticalLineTo(19f)
                horizontalLineTo(19f)
                verticalLineTo(13f)
                moveTo(14f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(10f)
                moveTo(20f, 4f)
                lineTo(11f, 13f)
            }
        }
    }

    val Download: ImageVector by lazy {
        glyph("Download") {
            line {
                moveTo(12f, 4f)
                verticalLineTo(15f)
                moveTo(7f, 10f)
                lineTo(12f, 15f)
                lineTo(17f, 10f)
                moveTo(5f, 20f)
                horizontalLineTo(19f)
            }
        }
    }

    val Filter: ImageVector by lazy {
        glyph("Filter") {
            line {
                moveTo(4f, 5f)
                horizontalLineTo(20f)
                lineTo(14f, 12f)
                verticalLineTo(19f)
                lineTo(10f, 17f)
                verticalLineTo(12f)
                close()
            }
        }
    }
}
