package io.github.munzzyy.tern.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.github.munzzyy.tern.ui.theme.LocalLook

private val RING_GAP = 2.dp
private val PILL_MAX_HEIGHT = 56.dp
private const val FOCUS_SCALE = 1.03f
private const val FOCUS_TONE = 0.12f
private const val FOCUS_MS = 150

/** Counts the controls under one [focusHighlight] that have focus and draw it themselves. */
private class OwnFocus {
    var drawn by mutableIntStateOf(0)
}

private val LocalOwnFocus = modifierLocalOf<OwnFocus?> { null }

/** A ring in the primary colour while the next focus target in the chain has focus. Put it before that target. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val width = LocalLook.current.focusOutline
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}

/**
 * The scale a focused element takes: 1.03, or less where that would need more than the room it
 * has, so it never reaches what is next to it.
 */
fun focusScale(width: Float, height: Float, roomX: Float, roomY: Float): Float {
    if (width <= 0f || height <= 0f) return 1f
    return minOf(FOCUS_SCALE, 1 + 2 * roomX / width, 1 + 2 * roomY / height).coerceAtLeast(1f)
}

/**
 * Focus the way Tern shows it under a remote or a keyboard: an outline, a lift of the tone and
 * a scale of 1.03, all inside the element's own room. Put it before the clickable it belongs to,
 * and leave `LocalLook.focusRoom` free to its left and right and half of that above and below.
 * [ring] is for an element filled with the accent itself, on which an outline in the accent
 * would not be seen.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusLook(shape: Shape? = null, ring: Color = Color.Unspecified): Modifier = composed {
    val look = LocalLook.current
    val outline = shape ?: MaterialTheme.shapes.medium
    val color = if (ring == Color.Unspecified) MaterialTheme.colorScheme.primary else ring
    val keys = drivenByKeys()
    val window = LocalWindowInfo.current
    var focused by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf<OwnFocus?>(null) }
    val shown by animateFloatAsState(if (focused && keys && window.isWindowFocused) 1f else 0f, tween(FOCUS_MS), label = "focus")
    DisposableEffect(host, focused) {
        val counted = host.takeIf { focused }
        counted?.let { it.drawn++ }
        onDispose { counted?.let { it.drawn-- } }
    }
    modifierLocalConsumer { host = LocalOwnFocus.current }
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            val most = focusScale(size.width, size.height, look.focusRoom.toPx(), look.focusRoom.toPx() / 2)
            val scale = 1 + (most - 1) * shown
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (shown > 0f) {
                val stroke = look.focusOutline.toPx()
                val inside = outline.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
                translate(stroke / 2, stroke / 2) {
                    drawOutline(inside, color, alpha = FOCUS_TONE * shown, style = Fill)
                    drawOutline(inside, color, alpha = shown, style = Stroke(stroke))
                }
            }
        }
}

/**
 * Draws focus for every control in the window that does not draw it with [focusLook]: an outline
 * and a lift of the tone around whichever has focus under keys. It sits at the root of a window.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.focusHighlight(): Modifier = composed {
    val keys = drivenByKeys()
    val window = LocalWindowInfo.current
    val color = MaterialTheme.colorScheme.primary
    val width = LocalLook.current.focusOutline
    val rowShape = MaterialTheme.shapes.medium
    val own = remember { OwnFocus() }
    var self by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    var target by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    modifierLocalProvider(LocalOwnFocus) { own }
        .onGloballyPositioned { self = it }
        .onFocusedBoundsChanged { target = it }
        .drawWithContent {
            drawContent()
            val root = self ?: return@drawWithContent
            val focused = target ?: return@drawWithContent
            if (own.drawn > 0 || !keys || !window.isWindowFocused || !root.isAttached || !focused.isAttached) return@drawWithContent
            val bounds = root.localBoundingBoxOf(focused, clipBounds = true)
            if (bounds.isEmpty) return@drawWithContent
            val ring = ringRect(bounds, RING_GAP.toPx(), width.toPx())
            val rowCorner = rowShape.topStart.toPx(bounds.size, this)
            val corner = CornerRadius(ringCorner(bounds.height, PILL_MAX_HEIGHT.toPx(), rowCorner, RING_GAP.toPx()))
            drawRoundRect(color, ring.topLeft, ring.size, corner, alpha = FOCUS_TONE)
            drawRoundRect(color, ring.topLeft, ring.size, corner, style = Stroke(width.toPx()))
        }
}

/** The stroke is centred on the rectangle, so it is pushed out by the gap plus half its width to sit clear of the control. */
fun ringRect(bounds: Rect, gap: Float, width: Float): Rect = bounds.inflate(gap + width / 2)

/** Buttons and chips get a pill, taller rows the corner of the shape scale. */
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
