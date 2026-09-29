package io.github.munzzyy.stamp.ui.handoff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun lines(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()

/** Drawn like the glyphs in `ui/icons`: lines two units wide on a square of 24. */
object HandoffGlyphs {
    val Phone: ImageVector by lazy {
        lines("Phone") {
            moveTo(9f, 2.5f)
            horizontalLineTo(15f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineTo(19.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(9f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(4.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            close()
            moveTo(11f, 18f)
            horizontalLineTo(13f)
        }
    }

    val Link: ImageVector by lazy {
        lines("Link") {
            moveTo(10f, 7f)
            horizontalLineTo(7f)
            arcToRelative(5f, 5f, 0f, false, false, 0f, 10f)
            horizontalLineTo(10f)
            moveTo(14f, 7f)
            horizontalLineTo(17f)
            arcToRelative(5f, 5f, 0f, false, true, 0f, 10f)
            horizontalLineTo(14f)
            moveTo(8.5f, 12f)
            horizontalLineTo(15.5f)
        }
    }

    val File: ImageVector by lazy {
        lines("File") {
            moveTo(6f, 3f)
            horizontalLineTo(14f)
            lineTo(19f, 8f)
            verticalLineTo(21f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 3f)
            verticalLineTo(8f)
            horizontalLineTo(19f)
        }
    }

    val Folder: ImageVector by lazy {
        lines("Folder") {
            moveTo(3f, 6f)
            horizontalLineTo(10f)
            lineTo(12f, 8.5f)
            horizontalLineTo(21f)
            verticalLineTo(19f)
            horizontalLineTo(3f)
            close()
        }
    }
}
