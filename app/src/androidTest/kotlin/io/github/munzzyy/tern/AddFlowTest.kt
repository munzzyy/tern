package io.github.munzzyy.tern

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munzzyy.tern.fake.FakeLinks
import io.github.munzzyy.tern.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIND_TAG
import io.github.munzzyy.tern.ui.add.ADD_INSTALL_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun putOnClipboard(text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
        }
    }

    @Test
    fun pasteShowsThePreviewAndAddOnlyAddsAfterConfirming() {
        launch("default").use {
            compose.onNodeWithText("Add").performClick()
            putOnClipboard(FakeLinks.NEW_APP)
            compose.onNodeWithText("Paste").performClick()
            compose.tagged(ADD_FIELD_TAG).assertTextContains(FakeLinks.NEW_APP)

            compose.tagged(ADD_FIND_TAG).performClick()
            compose.waitForText("Sparrow")
            compose.onNodeWithText("File to install").performScrollTo().assertIsDisplayed()
            compose.tagged(ADD_INSTALL_TAG).performScrollTo().assertIsDisplayed()
            assertTrue("nothing is added before the user confirms", fake.apps.value.none { it.id == "sparrow" })

            compose.tagged(ADD_CONFIRM_TAG).performScrollTo().performClick()
            compose.waitFor(hasText("Install") and androidx.compose.ui.test.hasTestTag(DETAIL_PRIMARY_TAG))
            val added = fake.apps.value.single { it.id == "sparrow" }
            assertEquals("Sparrow", added.config.name)
            assertEquals(null, added.progress)
        }
    }

    @Test
    fun plainTextSearchesAndPickingAResultRunsDetect() {
        launch("default").use {
            compose.onNodeWithText("Add").performClick()
            putOnClipboard("feed reader")
            compose.onNodeWithText("Paste").performClick()
            compose.tagged(ADD_FIND_TAG).performClick()
            compose.waitForText("results for")
            compose.onNodeWithText("Wren").performClick()
            compose.waitForText("The newest release is a pre-release")
            compose.onNodeWithText("The publisher offers no checksum for this file").performScrollTo().assertIsDisplayed()
        }
    }
}
