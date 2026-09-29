package io.github.munzzyy.jackdaw.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.common.ProblemBox
import io.github.munzzyy.jackdaw.ui.common.TrustLine
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.text.Trust

const val MOVED_FOLLOW_TAG = "moved_follow"
const val MOVED_KEEP_TAG = "moved_keep"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovedBox(movedTo: String?, state: MoveState, vm: DetailViewModel) {
    val actions = rememberActions()
    val failed = stringResource(R.string.action_failed)
    val online = LocalOnline.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 840.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (movedTo == null) {
            if (state == MoveState.Followed) TrustLine(Trust.GOOD, stringResource(R.string.moved_done))
            return@Column
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.moved_title, movedTo), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.moved_explain), style = MaterialTheme.typography.bodyMedium)
                if (state == MoveState.Working) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.moved_working), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.followMove { actions.say(failed) } },
                        enabled = state != MoveState.Working && online,
                        modifier = Modifier.testTag(MOVED_FOLLOW_TAG),
                    ) { Text(stringResource(R.string.moved_follow)) }
                    TextButton(
                        onClick = { vm.keepAddress { actions.say(failed) } },
                        enabled = state != MoveState.Working,
                        modifier = Modifier.testTag(MOVED_KEEP_TAG),
                    ) { Text(stringResource(R.string.moved_keep)) }
                }
            }
        }
        if (state is MoveState.Refused) ProblemBox(title = state.problem.message, body = null)
    }
}
