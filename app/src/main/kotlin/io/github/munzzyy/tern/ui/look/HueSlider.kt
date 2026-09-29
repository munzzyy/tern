package io.github.munzzyy.tern.ui.look

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlin.math.roundToInt

const val LOOK_HUE_TAG = "look_hue"
const val HUE_KEY_STEP = 5
private const val LAST_HUE = 359

/** The hue a key press leads to. Hue is a circle, so the ends join and the slider never stops a remote. */
fun hueAfterKey(hue: Int, forward: Boolean): Int = ((hue + if (forward) HUE_KEY_STEP else -HUE_KEY_STEP) % 360 + 360) % 360

const val TRACK_SEGMENTS = 72

/** The hue each segment of the track shows: the one in its middle, so the track runs once around the circle. */
fun trackHues(segments: Int = TRACK_SEGMENTS): List<Int> = List(segments) { (it * 360 + 180) / segments }

/**
 * Where the segments of a track from [start] to [end] begin and end, on whole pixels. Neighbours
 * share an edge, so no line of the background shows between two of them.
 */
fun segmentEdges(start: Float, end: Float, segments: Int = TRACK_SEGMENTS): FloatArray =
    FloatArray(segments + 1) { index ->
        when (index) {
            0 -> start
            segments -> end
            else -> (start + (end - start) * index / segments).roundToInt().toFloat()
        }
    }

/** The hue under a finger at [x] on a track of [width], which runs the other way in a right-to-left language. */
fun hueAt(x: Float, width: Float, rightToLeft: Boolean): Int {
    if (width <= 0f) return 0
    val along = (x / width).coerceIn(0f, 1f)
    return ((if (rightToLeft) 1 - along else along) * LAST_HUE).roundToInt()
}

/**
 * [onChange] follows the finger, [onSettled] is called when it lifts. Left and right move by
 * five degrees and settle at once.
 */
@Composable
fun HueSlider(
    hue: Int,
    colorOf: (Int) -> Color,
    onChange: (Int) -> Unit,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current by rememberUpdatedState(hue)
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    val name = stringResource(R.string.look_hue)
    val degrees = pluralStringResource(R.plurals.look_hue_degrees, hue, hue)
    val track = remember(colorOf) { trackHues().map(colorOf) }
    val thumbColor = remember(colorOf, hue) { colorOf(hue) }
    val edge = MaterialTheme.colorScheme.surface
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.focusRoom / 2)
            .testTag(LOOK_HUE_TAG)
            .focusLook(CircleShape)
            .onKeyEvent { event ->
                val forward = when (event.key) {
                    Key.DirectionRight -> !rightToLeft
                    Key.DirectionLeft -> rightToLeft
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) settled(hueAfterKey(current, forward))
                true
            }
            .semantics(mergeDescendants = true) {
                contentDescription = name
                stateDescription = degrees
                progressBarRangeInfo = ProgressBarRangeInfo(hue.toFloat(), 0f..LAST_HUE.toFloat())
                setProgress { wanted ->
                    settled(wanted.roundToInt().coerceIn(0, LAST_HUE))
                    true
                }
            }
            .focusable()
            .pointerInput(rightToLeft) {
                detectTapGestures { at -> settled(hueAt(at.x - inset(size.height), size.width - 2 * inset(size.height), rightToLeft)) }
            }
            .pointerInput(rightToLeft) {
                var last = current
                detectHorizontalDragGestures(
                    onDragStart = { last = current },
                    onDragEnd = { settled(last) },
                    onDragCancel = { settled(last) },
                ) { touch, _ ->
                    touch.consume()
                    last = hueAt(touch.position.x - inset(size.height), size.width - 2 * inset(size.height), rightToLeft)
                    change(last)
                }
            }
            .height(look.touchTarget),
    ) {
        // Plain rectangles and circles only, in colours worked out once and not on every frame.
        Canvas(Modifier.fillMaxSize()) {
            val side = inset(size.height.roundToInt())
            val thick = (size.height / 3).roundToInt().toFloat()
            val top = ((size.height - thick) / 2).roundToInt().toFloat()
            val colors = if (rightToLeft) track.asReversed() else track
            val edges = segmentEdges(side, size.width - side, colors.size)
            drawCircle(colors.first(), thick / 2, Offset(side, top + thick / 2))
            drawCircle(colors.last(), thick / 2, Offset(size.width - side, top + thick / 2))
            for (index in colors.indices) {
                drawRect(colors[index], Offset(edges[index], top), Size(edges[index + 1] - edges[index], thick))
            }
            val along = hue / LAST_HUE.toFloat()
            val x = side + (size.width - 2 * side) * (if (rightToLeft) 1 - along else along)
            val thumb = size.height * 0.3f
            drawCircle(edge, thumb + thick / 6, Offset(x, size.height / 2))
            drawCircle(thumbColor, thumb, Offset(x, size.height / 2))
            drawCircle(ring, thumb, Offset(x, size.height / 2), style = Stroke(thick / 6))
        }
    }
}

/** The thumb is a circle, so the track starts and ends half a thumb in from the sides. */
private fun inset(height: Int): Float = height / 2f
