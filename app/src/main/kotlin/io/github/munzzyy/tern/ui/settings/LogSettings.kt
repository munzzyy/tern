package io.github.munzzyy.tern.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.SwitchRow

/** What the activity log keeps besides what happened to apps. */
@Composable
internal fun LogSection(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    SectionCard(title = stringResource(R.string.journal_section)) {
        SwitchRow(
            title = stringResource(R.string.journal_keep_own),
            summary = stringResource(R.string.journal_keep_own_effect),
            checked = s.keepOwnMessages,
            onChange = { v -> update { it.copy(keepOwnMessages = v) } },
        )
    }
}
