package io.github.munzzyy.jackdaw.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.ui.common.FileChoiceView
import io.github.munzzyy.jackdaw.ui.common.TrustLine
import io.github.munzzyy.jackdaw.ui.common.VerificationPanel
import io.github.munzzyy.jackdaw.ui.common.sourceText
import io.github.munzzyy.jackdaw.ui.icons.LetterAvatar
import io.github.munzzyy.jackdaw.ui.text.Trust
import io.github.munzzyy.jackdaw.ui.text.formatDate
import io.github.munzzyy.jackdaw.ui.text.isolate
import io.github.munzzyy.jackdaw.ui.text.knownVersion

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewCard(found: Detection.Found, carried: List<CarriedSetting>, onAdd: (install: Boolean) -> Unit, onShow: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LetterAvatar(found.spec.url, found.name, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(found.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    found.author?.let {
                        Text(stringResource(R.string.by_author, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        sourceText(found.spec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            found.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val release = found.release
                if (release == null) {
                    Text(stringResource(R.string.preview_no_release), style = MaterialTheme.typography.bodyLarge)
                } else {
                    val published = release.publishedAtMs?.let { isolate(formatDate(it)) }
                    Text(
                        knownVersion(release.version)?.let { stringResource(R.string.preview_version, isolate(it)) }
                            ?: stringResource(R.string.version_unknown),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val extras = listOfNotNull(
                        published?.let { stringResource(R.string.published_on, it) },
                        if (release.prerelease) stringResource(R.string.prerelease) else null,
                    )
                    if (extras.isNotEmpty()) {
                        Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                found.installed?.let {
                    Text(
                        stringResource(R.string.preview_installed, isolate(knownVersion(it.versionName) ?: it.versionCode.toString())),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (found.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (w in found.warnings) TrustLine(Trust.BAD, w)
                }
            }

            found.file?.let {
                HorizontalDivider()
                Text(stringResource(R.string.preview_file), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                FileChoiceView(it)
                if (found.otherFiles.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.preview_other_files, found.otherFiles.size, found.otherFiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            found.verification?.let {
                HorizontalDivider()
                VerificationPanel(it)
            }

            if (carried.isNotEmpty() && found.alreadyTracked == null) {
                HorizontalDivider()
                CarriedSection(carried)
            }

            HorizontalDivider()
            val tracked = found.alreadyTracked
            if (tracked != null) {
                Text(stringResource(R.string.preview_already_tracked), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onShow(tracked) }) { Text(stringResource(R.string.action_show_it)) }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = { onAdd(false) }, modifier = Modifier.testTag(ADD_CONFIRM_TAG)) {
                        Text(stringResource(R.string.action_add))
                    }
                    if (found.file != null) {
                        Button(onClick = { onAdd(true) }, modifier = Modifier.testTag(ADD_INSTALL_TAG)) {
                            Text(stringResource(R.string.action_add_and_install))
                        }
                    }
                }
            }
        }
    }
}
