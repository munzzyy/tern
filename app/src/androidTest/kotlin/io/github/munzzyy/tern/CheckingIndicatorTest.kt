package io.github.munzzyy.tern

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.fake.FakeEngine
import io.github.munzzyy.tern.ui.common.lacksTouch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** While every app is checked, a device with touch shows the pull spinner and the bar; one without, the bar alone, since nobody pulls there. */
@RunWith(AndroidJUnit4::class)
class CheckingIndicatorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun aRunningCheckIsTheBarAloneWithoutTouch() {
        launch(FakeEngine.BARE).use {
            fake.stepMs = 1_000
            compose.onNodeWithContentDescription("Check all apps now").performClick()
            compose.waitUntil(3_000) { fake.checkingAll.value }
            compose.waitForIdle()
            val shown = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size
            assertEquals(if (appContext.lacksTouch()) 1 else 2, shown)
        }
    }
}
