package io.github.munzzyy.tern.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munzzyy.tern.ui.LocalReducedMotion
import io.github.munzzyy.tern.ui.theme.status

/** The seal as fractions of its outer radius, the same drawing as the launcher icon. */
object SealShape {
    const val TILT = -8f
    const val RING = 0.9083f
    const val RING_WIDTH = 0.1833f
    const val INNER_RING = 0.7f
    const val INNER_RING_WIDTH = 0.0533f
    const val CHECK_WIDTH = 0.2167f
    val CHECK = listOf(-0.3833f to 0.0167f, -0.1167f to 0.2833f, 0.4f to -0.3333f)
}

/**
 * The seal as it is pressed on, for the moment a file has passed all its checks. It is pressed on once,
 * then stays. With animations off in Android it is simply there. It has no words of its own:
 * say what it means next to it.
 */
@Composable
fun Seal(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = MaterialTheme.status.verified.color, press: Boolean = true) {
    val still = LocalReducedMotion.current || !press
    var pressed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (still || pressed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
        pressed = true
    }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val radius = this.size.minDimension / 2
        val shown = progress.value
        scale(1.4f - 0.4f * shown) {
            rotate(SealShape.TILT) {
                val ink = color.copy(alpha = color.alpha * shown.coerceIn(0f, 1f))
                drawCircle(ink, radius * SealShape.RING, style = Stroke(radius * SealShape.RING_WIDTH))
                drawCircle(ink, radius * SealShape.INNER_RING, style = Stroke(radius * SealShape.INNER_RING_WIDTH))
                val check = Path()
                SealShape.CHECK.forEachIndexed { index, (x, y) ->
                    val at = Offset(center.x + x * radius, center.y + y * radius)
                    if (index == 0) check.moveTo(at.x, at.y) else check.lineTo(at.x, at.y)
                }
                drawPath(check, ink, style = Stroke(radius * SealShape.CHECK_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
