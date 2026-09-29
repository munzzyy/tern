package io.github.munzzyy.jackdaw

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WaitingInstallTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun brushDraw() = fake.apps.value.single { it.id == "brushdraw" }

    @Test
    fun confirmFromTheListFinishesTheInstall() {
        launch("default").use {
            compose.shownRow("Brush Draw").performCustomAccessibilityActionWithLabel("Confirm")
            compose.waitUntil(10_000) { brushDraw().status == AppStatus.UP_TO_DATE && brushDraw().progress == null }
            assertEquals("0.6", brushDraw().installed?.versionName)
        }
    }

    @Test
    fun detailOffersConfirmAndCancel() {
        launch("default").use {
            compose.shownRow("Brush Draw").performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Confirm"))
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5_000) { brushDraw().progress == null }
        }
    }

    @Test
    fun whenNothingWaitsAnyMoreACheckPutsTheRowRight() {
        launch("default").use {
            fake.nothingWaits = true
            compose.shownRow("Brush Draw").performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Confirm"))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitUntil(5_000) { brushDraw().progress == null && brushDraw().lastCheckedMs != null && !brushDraw().checking }
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            assertNull(brushDraw().progress)
        }
    }
}
