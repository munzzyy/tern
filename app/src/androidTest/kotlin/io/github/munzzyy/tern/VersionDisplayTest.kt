package io.github.munzzyy.tern

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
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
            compose.shownRow("Tagged Weather").assert(hasContentDescription("Tagged Weather. Up to date. Version 0.4.4. Released", substring = true))
            compose.row("Tagged Weather").performClick()
            compose.onNodeWithText("\u20680.4.4\u2069").assertIsDisplayed()
            assertEquals(0, compose.textCount("to v0.4.4"))
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("Versions"))
            compose.waitForText("Installed")
            assertEquals(0, compose.onAllNodes(hasContentDescription("Install version v0.4.4")).fetchSemanticsNodes().size)
        }
    }

    /** Vault Keys runs 12.0 and is offered 12.1, with 12.0.1 and 12.0.2 in between. */
    @Test
    fun theNotesOfEveryReleaseSinceTheInstalledOneAreShown() {
        launch("default").use {
            compose.shownRow("Vault Keys").performClick()
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasText("What's new since", substring = true))
            compose.onNodeWithText("What's new since \u206812.0\u2069").assertIsDisplayed()
            for (version in listOf("12.1", "12.0.2", "12.0.1")) compose.waitForText("Version $version fixes a crash")
            assertEquals(0, compose.textCount("Version 12.0 fixes a crash", substring = true))
            assertEquals(0, compose.textCount("What's new in", substring = true))
        }
    }

    @Test
    fun aHeldCertificateIsNotCreditedToTheUser() {
        launch("default").use {
            compose.shownRow("Vault Keys").performClick()
            compose.waitForText("certificate Tern holds for this app")
            assertEquals(0, compose.onAllNodes(hasText("you pinned", substring = true)).fetchSemanticsNodes().size)
            for (id in listOf(R.string.signer_matches_pin, R.string.signer_matches_pin_claimed)) {
                val words = appContext.getString(id)
                assertTrue(words, "Tern holds for this app" in words && " you" !in words)
            }
        }
    }
}
