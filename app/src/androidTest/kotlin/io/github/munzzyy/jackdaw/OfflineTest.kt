package io.github.munzzyy.jackdaw

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.fake.FakeLinks
import io.github.munzzyy.jackdaw.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.jackdaw.ui.add.ADD_FIND_TAG
import io.github.munzzyy.jackdaw.ui.common.OFFLINE_BANNER_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val reason = "Unavailable without an internet connection"
    private val saysWhy = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, reason)

    @Test
    fun appsSaysSoOnceAndStopsRefresh() {
        launch("offline").use {
            compose.tagged(OFFLINE_BANNER_TAG).assertIsDisplayed()
            compose.onNodeWithText("No internet connection. Jackdaw checks again when it is back.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Check all apps now").assertIsNotEnabled().assert(saysWhy)
            compose.onNodeWithText("Update all").assertIsNotEnabled().assert(saysWhy)
            compose.shownRow("Pocket Notes")
            assertEquals(0, compose.onAllNodes(hasContentDescription("Check failed", substring = true)).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodes(hasContentDescription("Could not reach", substring = true)).fetchSemanticsNodes().size)
            compose.row("Pocket Notes").assert(hasContentDescription("Waiting for connection", substring = true))
        }
    }

    @Test
    fun addKeepsFindOffWithAReason() {
        launch("offline").use {
            compose.onNodeWithText("Add").performClick()
            compose.tagged(OFFLINE_BANNER_TAG).assertIsDisplayed()
            compose.tagged(ADD_FIELD_TAG).performTextReplacement(FakeLinks.NEW_APP)
            compose.tagged(ADD_FIND_TAG).assertIsNotEnabled().assert(saysWhy)
        }
    }

    @Test
    fun onlineShowsNoBannerAndFindWorks() {
        launch("default").use {
            assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag(OFFLINE_BANNER_TAG)).fetchSemanticsNodes().size)
            compose.onNodeWithText("Add").performClick()
            compose.tagged(ADD_FIELD_TAG).performTextReplacement(FakeLinks.NEW_APP)
            compose.tagged(ADD_FIND_TAG).assertIsEnabled()
        }
    }
}
