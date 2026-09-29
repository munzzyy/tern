package io.github.munzzyy.jackdaw.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

private val RING_WIDTH = 3.dp
private val RING_GAP = 2.dp
private val RING_CORNER = 12.dp
private val PILL_MAX_HEIGHT = 56.dp

/** A 3dp ring in the primary colour while the next focus target in the chain has focus. Put it before that target. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(RING_CORNER)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(RING_WIDTH, color, shape) else Modifier)
}

/** One ring around whichever control inside has focus under keys; Material's own focus tint is too faint across a room. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusHighlight(): Modifier = composed {
    val keys = drivenByKeys()
    val color = MaterialTheme.colorScheme.primary
    var own by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    var target by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    onGloballyPositioned { own = it }
        .onFocusedBoundsChanged { target = it }
        .drawWithContent {
            drawContent()
            val self = own ?: return@drawWithContent
            val focused = target ?: return@drawWithContent
            if (!keys || !self.isAttached || !focused.isAttached) return@drawWithContent
            val bounds = self.localBoundingBoxOf(focused, clipBounds = true)
            if (bounds.isEmpty) return@drawWithContent
            val ring = ringRect(bounds, RING_GAP.toPx(), RING_WIDTH.toPx())
            val corner = ringCorner(bounds.height, PILL_MAX_HEIGHT.toPx(), RING_CORNER.toPx(), RING_GAP.toPx())
            drawRoundRect(color, ring.topLeft, ring.size, CornerRadius(corner), style = Stroke(RING_WIDTH.toPx()))
        }
}

/** The stroke is centred on the rectangle, so it is pushed out by the gap plus half its width to sit clear of the control. */
fun ringRect(bounds: Rect, gap: Float, width: Float): Rect = bounds.inflate(gap + width / 2)

/** Buttons and chips get a pill, taller rows a gently rounded box. */
fun ringCorner(height: Float, pillMaxHeight: Float, rowCorner: Float, gap: Float): Float =
    if (height <= pillMaxHeight) height / 2 + gap else rowCorner

/** Lets up and down on a D-pad or arrow keys leave a single-line field instead of being swallowed by it. */
internal fun Modifier.verticalKeysLeave(): Modifier = composed {
    val focus = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
