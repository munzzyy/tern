package io.github.munzzyy.tern.ui.add

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.FileChoiceView
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.TrustLine
import io.github.munzzyy.tern.ui.common.VerificationPanel
import io.github.munzzyy.tern.ui.common.sourceText
import io.github.munzzyy.tern.ui.icons.LetterAvatar
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.text.formatDate
import io.github.munzzyy.tern.ui.text.hostOf
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.knownVersion
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.LocalOutlines
import io.github.munzzyy.tern.ui.theme.figures
import io.github.munzzyy.tern.ui.theme.heavier
import kotlinx.coroutines.CancellationException

const val PREVIEW_TAG = "add_preview"
const val PREVIEW_PIN_TAG = "add_preview_pin"

/**
 * What was found, before anything is stored. What the user has to know first stands first: which
 * app, which version, what is wrong with it, what the link would set. Then the two actions, then
 * the file and its checks for whoever wants to read them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewCard(found: Detection.Found, carried: List<CarriedSetting>, onAdd: (install: Boolean) -> Unit, onShow: (String) -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PREVIEW_TAG),
    ) {
        Column(Modifier.padding(look.cardPadding), verticalArrangement = Arrangement.spacedBy(look.gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(look.gap), verticalAlignment = Alignment.CenterVertically) {
                FoundIcon(found, look.iconHeader)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4)) {
                    Text(found.name, style = MaterialTheme.typography.titleLarge.heavier(), modifier = Modifier.semantics { heading() })
                    found.author?.let {
                        Text(stringResource(R.string.by_author, it), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    Text(sourceText(found.spec), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            found.description?.takeIf { it.isNotBlank() }?.let { Text(brief(it, MAX_PREVIEW_DESCRIPTION), style = MaterialTheme.typography.bodyLarge) }

            Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
                val release = found.release
                if (release == null) {
                    // A warning below already says what is wrong, such as a release with no file for this device.
                    if (found.warnings.isEmpty()) Text(stringResource(R.string.preview_no_release), style = MaterialTheme.typography.bodyLarge)
                } else {
                    val published = release.publishedAtMs?.let { isolate(formatDate(it)) }
                    Text(
                        knownVersion(release.version)?.let { stringResource(R.string.preview_version, isolate(it)) }
                            ?: stringResource(R.string.version_unknown),
                        style = MaterialTheme.typography.titleMedium.figures(),
                    )
                    val extras = listOfNotNull(
                        published?.let { stringResource(R.string.published_on, it) },
                        if (release.prerelease) stringResource(R.string.prerelease) else null,
                    )
                    if (extras.isNotEmpty()) {
                        Text(extras.joinToString(" \u00B7 "), style = MaterialTheme.typography.bodyMedium.figures(), color = scheme.onSurfaceVariant)
                    }
                }
                found.installed?.let {
                    Text(
                        stringResource(R.string.preview_installed, isolate(knownVersion(it.versionName) ?: it.versionCode.toString())),
                        style = MaterialTheme.typography.bodyMedium.figures(),
                    )
                }
            }

            val origin = originNote(found.spec.type)
            if (found.warnings.isNotEmpty() || origin != null) {
                Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                    when (origin) {
                        OriginNote.MODIFIED -> TrustLine(Trust.BAD, stringResource(R.string.preview_modified, sourceName(found.spec)))
                        OriginNote.REPUBLISHED -> TrustLine(Trust.NOTE, stringResource(R.string.preview_republished, sourceName(found.spec)))
                        null -> Unit
                    }
                    for (w in found.warnings.take(MAX_WARNINGS)) TrustLine(Trust.BAD, w)
                }
            }

            val tracked = found.alreadyTracked
            if (carried.isNotEmpty() && tracked == null) CarriedSection(carried)

            if (tracked != null) {
                Text(stringResource(R.string.preview_already_tracked), style = MaterialTheme.typography.bodyLarge)
                PrimaryButton(stringResource(R.string.action_show_it), onClick = { onShow(tracked) })
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(look.focusRoom),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TonalButton(stringResource(R.string.action_add), onClick = { onAdd(false) }, modifier = Modifier.testTag(ADD_CONFIRM_TAG))
                    if (found.file != null) {
                        PrimaryButton(stringResource(R.string.action_add_and_install), onClick = { onAdd(true) }, modifier = Modifier.testTag(ADD_INSTALL_TAG))
                    }
                }
            }

            found.file?.let {
                HorizontalDivider(color = scheme.outlineVariant)
                Text(stringResource(R.string.preview_file), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                FileChoiceView(it)
                if (found.otherFiles.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.preview_other_files, found.otherFiles.size, found.otherFiles.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            found.verification?.let {
                HorizontalDivider(color = scheme.outlineVariant)
                VerificationPanel(it)
                if (found.builtInPin) {
                    Column(Modifier.testTag(PREVIEW_PIN_TAG)) { TrustLine(Trust.GOOD, stringResource(R.string.preview_built_in_pin)) }
                }
            }
        }
    }
}

private const val MAX_PREVIEW_DESCRIPTION = 600
private const val MAX_WARNINGS = 12

/** The icon the source offers for an app that is not in the list yet, or a letter on a colour. The engine decides whether to ask for it. */
@Composable
fun FoundIcon(found: Detection.Found, size: Dp, modifier: Modifier = Modifier) {
    val engine = LocalEngine.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, engine, found.spec.url, found.iconUrls, px) {
        value = try {
            engine.icon(found, px)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = modifier.size(size).clip(LocalOutlines.current.icon).clearAndSetSemantics { })
    } else {
        LetterAvatar(found.spec.url, found.name, size, modifier)
    }
}

/** What an app's page says about where a file comes from, when it is not from the developer's own channels. */
enum class OriginNote { REPUBLISHED, MODIFIED }

fun originNote(type: String): OriginNote? = when (type) {
    in SourceTypes.MODIFIED -> OriginNote.MODIFIED
    in SourceTypes.REPUBLISHING -> OriginNote.REPUBLISHED
    else -> null
}

/** The store's own name, or its host where it has none. */
fun sourceName(spec: SourceSpec): String = SourceTypes.displayName(spec.type) ?: hostOf(spec.url)
