package io.github.munzzyy.stamp

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.ui.detail.DETAIL_LIST_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoVersionTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Every text and description on screen, with bidi isolates removed. */
    private fun allWords(): List<String> =
        compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.map { it.replace("\u2068", "").replace("\u2069", "") }

    private fun assertNoEmptyVersion() {
        val bad = allWords().filter { w ->
            "  " in w || w.contains(" to .") || w.contains(" to ,") || w.startsWith(", ") || w.contains("Version .") ||
                w.endsWith(" to") || w.contains("new in .") || w.endsWith("new in")
        }
        assertTrue("empty version printed in $bad", bad.isEmpty())
    }

    @Test
    fun installedAppWithAVersionlessReleaseSaysANewFileIsAvailable() {
        launch("default").use {
            compose.shownRow("Nightly Pad").assert(hasContentDescription("Version 1.4.2 installed, new file available", substring = true))
            assertNoEmptyVersion()
            compose.row("Nightly Pad").performClick()
            compose.onNodeWithText("\u20681.4.2\u2069, new file available").assertIsDisplayed()
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("What's new"))
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("Version unknown"))
            assertNoEmptyVersion()
        }
    }

    @Test
    fun newAppWithAVersionlessReleaseSaysTheVersionIsUnknown() {
        launch("default").use {
            compose.shownRow("Latest Build Viewer").assert(hasContentDescription("Version unknown until the file is read", substring = true))
            assertNoEmptyVersion()
            compose.row("Latest Build Viewer").performClick()
            compose.onNodeWithText("Version unknown until the file is read").assertIsDisplayed()
            assertNoEmptyVersion()
        }
    }
}
