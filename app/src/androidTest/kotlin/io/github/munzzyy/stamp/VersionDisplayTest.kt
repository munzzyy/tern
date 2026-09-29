package io.github.munzzyy.stamp

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.ui.detail.DETAIL_LIST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionDisplayTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun upToDateAppWithAVTagShowsOnlyItsVersion() {
        launch("default").use {
            compose.shownRow("Tagged Weather").assert(hasContentDescription("Tagged Weather. Up to date. Version 0.4.4.", substring = false))
            compose.row("Tagged Weather").performClick()
            compose.onNodeWithText("\u20680.4.4\u2069").assertIsDisplayed()
            assertEquals(0, compose.textCount("to v0.4.4"))
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("Versions"))
            compose.waitForText("Installed")
            assertEquals(0, compose.onAllNodes(hasContentDescription("Install version v0.4.4")).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aHeldCertificateIsNotCreditedToTheUser() {
        launch("default").use {
            compose.shownRow("Vault Keys").performClick()
            compose.waitForText("certificate Stamp holds for this app")
            assertEquals(0, compose.onAllNodes(hasText("you pinned", substring = true)).fetchSemanticsNodes().size)
            for (id in listOf(R.string.signer_matches_pin, R.string.signer_matches_pin_claimed)) {
                val words = appContext.getString(id)
                assertTrue(words, "Stamp holds for this app" in words && " you" !in words)
            }
        }
    }
}
