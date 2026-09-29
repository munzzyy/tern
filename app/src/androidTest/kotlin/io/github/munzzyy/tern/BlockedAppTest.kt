package io.github.munzzyy.tern

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val namesInstallOrUpdate = SemanticsMatcher("names an install or update") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        listOf("Install", "Update").any { text.startsWith(it) || desc.startsWith(it) }
    }

    private fun installControlsOnScreen(): Int =
        compose.onAllNodes(hasClickAction() and namesInstallOrUpdate).fetchSemanticsNodes().size

    @Test
    fun blockedAppSaysWhyAndOffersNoInstall() {
        launch("default").use {
            compose.shownRow("Ferry Book").performClick()

            compose.waitForText("signed with a different certificate")
            compose.onNodeWithText("The new file is signed with a different certificate than Ferry Book on this device.").assertIsDisplayed()
            compose.onNodeWithText("Android does not replace an app", substring = true).assertIsDisplayed()
            compose.tagged(DETAIL_PRIMARY_TAG).assertTextEquals("Check again")
            assertEquals(0, installControlsOnScreen())

            for (landmark in listOf("Versions", "File choice", "Advanced", "Remove")) {
                compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText(landmark))
                compose.waitForIdle()
                assertEquals("install or update control near \"$landmark\"", 0, installControlsOnScreen())
            }
        }
    }

    @Test
    fun theSameScreenOffersUpdateWhenNothingBlocksIt() {
        launch("default").use {
            compose.shownRow("Harbor Terminal").performClick()
            compose.waitFor(hasText("Update") and hasClickAction())
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("Versions"))
            compose.waitForText("Install this version")
        }
    }
}
