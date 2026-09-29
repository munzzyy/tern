package io.github.munzzyy.tern.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.ProblemBox
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.TrustLine
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.theme.LocalLook

const val MOVED_FOLLOW_TAG = "moved_follow"
const val MOVED_KEEP_TAG = "moved_keep"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovedBox(movedTo: String?, state: MoveState, vm: DetailViewModel) {
    val actions = rememberActions()
    val look = LocalLook.current
    val failed = stringResource(R.string.action_failed)
    val online = LocalOnline.current
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (movedTo == null) {
            if (state == MoveState.Followed) {
                Column(Modifier.padding(horizontal = look.screenPadding + look.focusRoom).widthIn(max = look.contentMaxWidth)) {
                    TrustLine(Trust.GOOD, stringResource(R.string.moved_done))
                }
            }
            return@Column
        }
        DetailCard(stringResource(R.string.moved_title, movedTo), padded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                Text(stringResource(R.string.moved_explain), style = MaterialTheme.typography.bodyMedium)
                if (state == MoveState.Working) {
                    Row(horizontalArrangement = Arrangement.spacedBy(look.gap), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(look.glyph))
                        Text(stringResource(R.string.moved_working), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2), verticalArrangement = Arrangement.spacedBy(look.focusRoom)) {
                    TonalButton(
                        stringResource(R.string.moved_follow),
                        onClick = { vm.followMove { actions.say(failed) } },
                        enabled = state != MoveState.Working && online,
                        modifier = Modifier.testTag(MOVED_FOLLOW_TAG),
                    )
                    QuietButton(
                        stringResource(R.string.moved_keep),
                        onClick = { vm.keepAddress { actions.say(failed) } },
                        enabled = state != MoveState.Working,
                        modifier = Modifier.testTag(MOVED_KEEP_TAG),
                    )
                }
            }
        }
        if (state is MoveState.Refused) {
            ProblemBox(
                title = state.problem.message,
                body = null,
                modifier = Modifier.padding(horizontal = look.screenPadding).widthIn(max = look.contentMaxWidth),
            )
        }
    }
}
