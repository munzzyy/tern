package io.github.munzzyy.tern.ui.look

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.theme.CUSTOM_STRENGTH
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.Role as ColorRole
import io.github.munzzyy.tern.ui.theme.dynamicColorSupported
import io.github.munzzyy.tern.ui.theme.tableColor
import io.github.munzzyy.tern.ui.theme.seed
import io.github.munzzyy.tern.ui.theme.wallpaperScheme

/** [fill] is one colour, or the colours of a wheel for the choice that stands for all of them. [chosen] and [mark] are drawn once it is taken. */
private class Swatch(val name: String, val fill: List<Color>, val chosen: Color, val mark: Color, val taken: Boolean, val pick: (Settings) -> Settings)

fun paletteName(palette: Palette): Int = when (palette) {
    Palette.INK -> R.string.palette_ink
    Palette.SLATE -> R.string.palette_slate
    Palette.TIDE -> R.string.palette_tide
    Palette.MOSS -> R.string.palette_moss
    Palette.AMBER -> R.string.palette_amber
    Palette.CLAY -> R.string.palette_clay
    Palette.ROSE -> R.string.palette_rose
    Palette.PLUM -> R.string.palette_plum
}

/** The accent a hue gives under the settings as they are, which is what the swatches and the slider show. */
fun accentOf(hue: Int, strength: Double, settings: Settings, dark: Boolean): Color =
    Color(tableColor(ColorRole.PRIMARY, hue, strength, dark, settings.contrast))

fun onAccentOf(hue: Int, strength: Double, settings: Settings, dark: Boolean): Color =
    Color(tableColor(ColorRole.ON_PRIMARY, hue, strength, dark, settings.contrast))

/** Wallpaper where Android has it, the eight palettes, and the user's own colour. Left and right move along them. */
@Composable
fun Swatches(settings: Settings, dark: Boolean, onPick: ((Settings) -> Settings) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val context = LocalContext.current
    val wallpaperName = stringResource(R.string.look_wallpaper)
    val ownName = stringResource(R.string.look_own)
    val names = Palette.entries.associateWith { stringResource(paletteName(it)) }
    val source = if (settings.colorSource == ColorSource.WALLPAPER && !dynamicColorSupported) ColorSource.PALETTE else settings.colorSource
    val swatches = remember(settings.colorSource, settings.palette, settings.customHue, settings.contrast, dark, names) {
        buildList {
            wallpaperScheme(context, settings.copy(colorSource = ColorSource.WALLPAPER), dark)?.let { scheme ->
                add(
                    Swatch(wallpaperName, listOf(scheme.primary), scheme.primary, scheme.onPrimary, source == ColorSource.WALLPAPER) {
                        it.copy(colorSource = ColorSource.WALLPAPER)
                    },
                )
            }
            for (palette in Palette.entries) {
                val seed = palette.seed
                add(
                    Swatch(
                        name = names.getValue(palette),
                        fill = listOf(accentOf(seed.hue, seed.strength, settings, dark)),
                        chosen = accentOf(seed.hue, seed.strength, settings, dark),
                        mark = onAccentOf(seed.hue, seed.strength, settings, dark),
                        taken = source == ColorSource.PALETTE && settings.palette == palette,
                    ) { it.copy(colorSource = ColorSource.PALETTE, palette = palette) },
                )
            }
            add(
                Swatch(
                    name = ownName,
                    fill = wheelHues().map { accentOf(it, CUSTOM_STRENGTH, settings, dark) },
                    chosen = accentOf(settings.customHue, CUSTOM_STRENGTH, settings, dark),
                    mark = onAccentOf(settings.customHue, CUSTOM_STRENGTH, settings, dark),
                    taken = source == ColorSource.CUSTOM,
                ) { it.copy(colorSource = ColorSource.CUSTOM) },
            )
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
    ) {
        Text(stringResource(R.string.look_palette), style = MaterialTheme.typography.bodyLarge)
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val least = with(LocalDensity.current) { (MaterialTheme.typography.labelMedium.fontSize * NAME_WIDTH).toDp() }
            val columns = columnsFor(maxWidth.value, least.value, swatches.size)
            val stops = remember(swatches.size) { List(swatches.size) { FocusRequester() } }
            Column(verticalArrangement = Arrangement.spacedBy(look.focusRoom)) {
                for ((row, line) in swatches.chunked(columns).withIndex()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2)) {
                        for ((column, swatch) in line.withIndex()) {
                            val index = row * columns + column
                            val place = Modifier
                                .weight(1f)
                                .focusRequester(stops[index])
                                .focusProperties {
                                    if (index > 0) start = stops[index - 1]
                                    if (index < stops.lastIndex) end = stops[index + 1]
                                }
                            SwatchItem(swatch, place) { onPick(swatch.pick) }
                        }
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

const val WHEEL_WEDGES = 12
private const val WHEEL_START = -90f

/** A wedge reaches a little under the next, so no line of the background shows between two of them. */
private const val WEDGE_OVERLAP = 0.6f

/** The hues of the wheel that stands for the user's own colour, one for each wedge. */
fun wheelHues(wedges: Int = WHEEL_WEDGES): List<Int> = List(wedges) { it * 360 / wedges }

/** A swatch is as wide as a name of one short word needs, in letters of the size the names are set in. */
private const val NAME_WIDTH = 6

/** As many swatches side by side as fit at [least] each, and never a last line with one swatch alone where that can be helped. */
fun columnsFor(width: Float, least: Float, count: Int): Int {
    val fit = (width / least).toInt().coerceIn(1, count.coerceAtLeast(1))
    return if (fit > 2 && count % fit == 1) fit - 1 else fit
}

@Composable
private fun SwatchItem(swatch: Swatch, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    val ring = MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
        modifier = modifier
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = swatch.taken, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = look.gapSmall / 2, vertical = look.gapSmall),
    ) {
        Canvas(Modifier.size(look.touchTarget)) {
            val radius = size.minDimension / 2
            val width = look.focusOutline.toPx()
            if (swatch.taken) {
                drawCircle(ring, radius - width / 2, style = Stroke(width))
                drawCircle(swatch.chosen, radius - width * 2)
                val check = Path()
                check.moveTo(center.x - radius * 0.3f, center.y + radius * 0.02f)
                check.lineTo(center.x - radius * 0.08f, center.y + radius * 0.24f)
                check.lineTo(center.x + radius * 0.32f, center.y - radius * 0.22f)
                drawPath(check, swatch.mark, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else if (swatch.fill.size > 1) {
                val turn = 360f / swatch.fill.size
                val corner = Offset(center.x - radius, center.y - radius)
                swatch.fill.forEachIndexed { index, color ->
                    drawArc(color, WHEEL_START + index * turn, turn + WEDGE_OVERLAP, useCenter = true, topLeft = corner, size = Size(radius * 2, radius * 2))
                }
            } else {
                drawCircle(swatch.fill.first(), radius)
            }
        }
        Text(
            swatch.name,
            style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
            color = if (swatch.taken) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
