package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.common.EXPLAIN_CHECKSUM_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_PACKAGE_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_PERMISSIONS_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_SIGNER_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_TEXT_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_TITLE_TAG
import io.github.munzzyy.tern.ui.common.EXPLAIN_VIRUSTOTAL_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.tern.ui.detail.EXPLAIN_PROMPT_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every check can be asked what it means, and answers in the words that fit the state it is in. */
@RunWith(AndroidJUnit4::class)
class ExplainTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun touch() = touchAgain()

    private fun open(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
    }

    private fun ask(tag: String, title: String, sentence: String) {
        assertEquals("nothing is explained before it is asked for", 0, compose.onAllNodes(hasTestTag(EXPLAIN_TEXT_TAG)).fetchSemanticsNodes().size)
        compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(tag))
        compose.tagged(tag).assertIsDisplayed().performClick()
        compose.waitFor(hasTestTag(EXPLAIN_TEXT_TAG))
        compose.tagged(EXPLAIN_TEXT_TAG).assertIsDisplayed().assertTextEquals(sentence)
        compose.tagged(EXPLAIN_TITLE_TAG).assertIsDisplayed().assertTextEquals(title)
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(EXPLAIN_TEXT_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theChecksOfAnInstalledAppExplainThemselves() {
        launch("default").use {
            open("Pocket Notes")
            ask(
                EXPLAIN_PACKAGE_TAG, "Package name",
                "Every Android app has one name that never changes. A file that carried another name would be a different app, whatever it called itself.",
            )
            ask(
                EXPLAIN_SIGNER_TAG, "Signing certificate",
                "A developer signs every release with a key only they hold. Tern compares the signature of this file with the app on this device and with the " +
                    "certificate it remembered at the first install. A file signed by anyone else is refused, even if it comes from the right address.",
            )
            ask(
                EXPLAIN_CHECKSUM_TAG, "Checksum",
                "A checksum is a fingerprint of the file itself. The publisher gave one, and the file Tern downloaded has the same, so it arrived as it was published.",
            )
            ask(EXPLAIN_VIRUSTOTAL_TAG, "VirusTotal", "Opens the page for this exact file at VirusTotal in your browser. Tern sends nothing there.")
            ask(EXPLAIN_PERMISSIONS_TAG, "Permissions", "What this version asks to be allowed to do that the installed one did not.")
        }
    }

    @Test
    fun aFirstInstallIsExplainedAsOne() {
        launch("default").use {
            open("Kestrel Mail")
            ask(
                EXPLAIN_SIGNER_TAG, "Signing certificate",
                "There is nothing to compare with yet. The file says who signed it, Android confirms that when it installs, and from then on Tern holds every " +
                    "update to the same certificate.",
            )
        }
    }

    @Test
    fun aPublisherWithoutAChecksumIsExplainedAsOne() {
        launch("default").use {
            open("Loom Reader")
            ask(EXPLAIN_CHECKSUM_TAG, "Checksum", "This publisher gives no checksum. The signature is still checked, and it is the stronger of the two.")
        }
    }

    @Test
    fun theLineAboutThePromptExplainsItself() {
        launch("default").use {
            open("Trail Map")
            compose.onNodeWithText("Android will ask you to confirm this update.").assertIsDisplayed()
            ask(
                EXPLAIN_PROMPT_TAG, "Updates without a prompt",
                "Android lets an app update another without asking when that app installed it and the device runs Android 12 or later. Android decides each time.",
            )
        }
    }

    @Test
    fun anExplanationOpensAndClosesWithTheRemote() {
        keysOnly()
        launch("default").use {
            compose.assertFocusOn(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions), "the list opens on its first app")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
            val button = hasTestTag(EXPLAIN_PACKAGE_TAG)
            compose.moveTo(button, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(EXPLAIN_TEXT_TAG))
            compose.tagged(EXPLAIN_TEXT_TAG).assertIsDisplayed()
            compose.assertFocusOn(hasText("Close"), "the explanation opens with focus on its way out")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(EXPLAIN_TEXT_TAG)).fetchSemanticsNodes().isEmpty() }
            compose.assertFocusOn(button, "closing the explanation gives focus back to the check that was asked")
        }
    }
}
