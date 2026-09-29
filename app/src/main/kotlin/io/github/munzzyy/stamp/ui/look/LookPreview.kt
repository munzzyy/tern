package io.github.munzzyy.stamp.ui.look

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.ui.common.ChipTone
import io.github.munzzyy.stamp.ui.common.StatusChip
import io.github.munzzyy.stamp.ui.common.TonalButton
import io.github.munzzyy.stamp.ui.icons.Glyphs
import io.github.munzzyy.stamp.ui.icons.LetterAvatar
import io.github.munzzyy.stamp.ui.text.isolate
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.LocalOutlines
import io.github.munzzyy.stamp.ui.theme.StampTheme
import io.github.munzzyy.stamp.ui.theme.figures
import io.github.munzzyy.stamp.ui.theme.heavier
import io.github.munzzyy.stamp.ui.theme.status

const val LOOK_PREVIEW_TAG = "look_preview"

/**
 * What the preview was last drawn with. A setting that does not reach the preview leaves this as
 * it was. [cut] is true when one of its texts did not fit and lost its end.
 */
data class Drawn(val primary: Int, val surface: Int, val rowHeight: Float, val corner: Float, val iconOutline: String, val cut: Boolean)

val LookDrawn = SemanticsPropertyKey<Drawn>("LookDrawn")

/** True when a text lost something: a line too high for its box, an end replaced by dots, or a line wider than its box. */
fun TextLayoutResult.isCut(): Boolean {
    if (didOverflowHeight) return true
    for (line in 0 until lineCount) {
        if (isLineEllipsized(line)) return true
        if (getLineRight(line) - getLineLeft(line) > size.width + 0.5f) return true
    }
    return false
}

private const val STACK_FONT_SCALE = 1.5f

/** A piece of the app under [settings], which need not be stored yet: an app row with its status and action, and one sentence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookPreview(settings: Settings, modifier: Modifier = Modifier) {
    StampTheme(settings) {
        val look = LocalLook.current
        val scheme = MaterialTheme.colorScheme
        val density = LocalDensity.current
        val spoken = stringResource(R.string.look_preview_spoken)
        val cut = remember { mutableStateMapOf<String, Boolean>() }
        val drawn = Drawn(
            primary = scheme.primary.toArgb(),
            surface = scheme.surface.toArgb(),
            rowHeight = look.rowHeight.value,
            corner = with(density) { MaterialTheme.shapes.medium.topStart.toPx(Size(200f, 200f), density).toDp().value },
            iconOutline = LocalOutlines.current.icon.toString(),
            cut = cut.values.any { it },
        )
        val stacked = density.fontScale >= STACK_FONT_SCALE
        val action: @Composable () -> Unit = { TonalButton(stringResource(R.string.action_update), onClick = {}) }
        Surface(
            color = scheme.surface,
            contentColor = scheme.onSurface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(look.focusOutline / 3, scheme.outlineVariant),
            modifier = modifier
                .fillMaxWidth()
                .testTag(LOOK_PREVIEW_TAG)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    this[LookDrawn] = drawn
                },
        ) {
            Column(Modifier.padding(vertical = look.gapSmall)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(look.gap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = look.rowHeight)
                        .padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
                ) {
                    LetterAvatar("look-preview", stringResource(R.string.look_preview_app), look.iconList)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
                        Text(
                            stringResource(R.string.look_preview_app),
                            style = MaterialTheme.typography.titleMedium.heavier(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { cut["name"] = it.isCut() },
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
                            verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusChip(Glyphs.Update, stringResource(R.string.status_update), tone = ChipTone.NOTICE)
                            Text(
                                stringResource(R.string.version_change, isolate("0.4.0"), isolate("0.4.4")),
                                style = MaterialTheme.typography.bodyMedium.figures(),
                                color = scheme.onSurfaceVariant,
                                onTextLayout = { cut["version"] = it.isCut() },
                            )
                        }
                    }
                    if (!stacked) action()
                }
                if (stacked) {
                    Row(Modifier.padding(start = look.rowPaddingHorizontal + look.iconList + look.gap, bottom = look.gapSmall)) { action() }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
                    modifier = Modifier.padding(horizontal = look.rowPaddingHorizontal, vertical = look.gapSmall / 2),
                ) {
                    Icon(Glyphs.Seal, contentDescription = null, tint = MaterialTheme.status.verified.color, modifier = Modifier.size(look.glyph))
                    Text(
                        stringResource(R.string.checksum_matched),
                        style = MaterialTheme.typography.bodyMedium,
                        onTextLayout = { cut["sentence"] = it.isCut() },
                    )
                }
            }
        }
    }
}
