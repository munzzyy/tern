package io.github.munzzyy.tern.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal fun glyph(name: String, autoMirror: Boolean = false, draw: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply(draw).build()

internal fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

internal fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = block)

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

    /** The mark of the app: a round seal with a check mark. */
    val Seal: ImageVector by lazy {
        glyph("Seal") {
            line {
                circle(12f, 12f, 9f)
                moveTo(8f, 12.5f)
                lineTo(11f, 15.5f)
                lineTo(16.5f, 9f)
            }
        }
    }

    val Check: ImageVector by lazy {
        glyph("Check") {
            line {
                moveTo(5f, 12.5f)
                lineTo(10f, 17.5f)
                lineTo(19f, 7.5f)
            }
        }
    }

    val Update: ImageVector by lazy {
        glyph("Update") {
            line {
                moveTo(12f, 20f)
                verticalLineTo(9f)
                moveTo(7f, 13.5f)
                lineTo(12f, 8.5f)
                lineTo(17f, 13.5f)
                moveTo(5f, 4f)
                horizontalLineTo(19f)
            }
        }
    }

    val Install: ImageVector by lazy {
        glyph("Install") {
            line {
                moveTo(12f, 4f)
                verticalLineTo(14f)
                moveTo(7.5f, 10f)
                lineTo(12f, 14.5f)
                lineTo(16.5f, 10f)
                moveTo(5f, 16f)
                verticalLineTo(20f)
                horizontalLineTo(19f)
                verticalLineTo(16f)
            }
        }
    }

    val Refused: ImageVector by lazy {
        glyph("Refused") {
            line {
                circle(12f, 12f, 9f)
                moveTo(5.6f, 5.6f)
                lineTo(18.4f, 18.4f)
            }
        }
    }

    val Failed: ImageVector by lazy {
        glyph("Failed") {
            line {
                circle(12f, 12f, 9f)
                moveTo(9f, 9f)
                lineTo(15f, 15f)
                moveTo(15f, 9f)
                lineTo(9f, 15f)
            }
        }
    }

    val Caution: ImageVector by lazy {
        glyph("Caution") {
            line {
                moveTo(12f, 4f)
                lineTo(21f, 19.5f)
                horizontalLineTo(3f)
                close()
                moveTo(12f, 10f)
                verticalLineTo(14f)
                moveTo(12f, 16.9f)
                verticalLineTo(17f)
            }
        }
    }

    val Waiting: ImageVector by lazy {
        glyph("Waiting") {
            line {
                circle(12f, 12f, 9f)
                moveTo(12f, 7.5f)
                verticalLineTo(12f)
                lineTo(15f, 14f)
            }
        }
    }

    val Busy: ImageVector by lazy {
        glyph("Busy") {
            line {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, true, true, 16.5f, 6.6f)
                moveTo(17f, 3f)
                verticalLineTo(7f)
                horizontalLineTo(13f)
            }
        }
    }

    val Queued: ImageVector by lazy {
        glyph("Queued") {
            solid {
                for (x in listOf(6f, 12f, 18f)) circle(x, 12f, 1.6f)
            }
        }
    }

    val Offline: ImageVector by lazy {
        glyph("Offline") {
            line {
                moveTo(3.5f, 10f)
                arcTo(12f, 12f, 0f, false, true, 20.5f, 10f)
                moveTo(7.5f, 14f)
                arcTo(6.5f, 6.5f, 0f, false, true, 16.5f, 14f)
                moveTo(12f, 18f)
                verticalLineTo(18.1f)
                moveTo(4f, 4f)
                lineTo(20f, 20f)
            }
        }
    }

    val Unknown: ImageVector by lazy {
        glyph("Unknown") {
            line {
                circle(12f, 12f, 9f)
                moveTo(9.6f, 9.6f)
                arcTo(2.5f, 2.5f, 0f, true, true, 12.9f, 12f)
                quadTo(12f, 12.6f, 12f, 13.6f)
                moveTo(12f, 16.4f)
                verticalLineTo(16.5f)
            }
        }
    }

    /** A drop of ink, for the way to the Look page. */
    val Drop: ImageVector by lazy {
        glyph("Drop") {
            line {
                moveTo(12f, 3.5f)
                curveTo(12f, 3.5f, 6f, 10f, 6f, 14.5f)
                arcTo(6f, 6f, 0f, false, false, 18f, 14.5f)
                curveTo(18f, 10f, 12f, 3.5f, 12f, 3.5f)
                close()
            }
        }
    }

    val ChevronEnd: ImageVector by lazy {
        glyph("ChevronEnd", autoMirror = true) {
            line {
                moveTo(9f, 6f)
                lineTo(15f, 12f)
                lineTo(9f, 18f)
            }
        }
    }
}

internal fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
    moveTo(x - radius, y)
    arcToRelative(radius, radius, 0f, true, true, radius * 2, 0f)
    arcToRelative(radius, radius, 0f, true, true, -radius * 2, 0f)
    close()
}
