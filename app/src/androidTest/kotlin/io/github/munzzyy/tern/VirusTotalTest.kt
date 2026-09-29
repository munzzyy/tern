package io.github.munzzyy.tern

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.common.VIRUSTOTAL_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirusTotalTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun open(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
    }

    @Test
    fun theLinkShowsTheWholeAddressAndWhatIsSentBeforeLeaving() {
        launch("default").use {
            open("Pocket Notes")
            val hash = fake.apps.value.single { it.id == "pocketnotes" }.verification!!.fileSha256!!
            compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(VIRUSTOTAL_TAG))
            compose.onNodeWithTag(VIRUSTOTAL_TAG).assertIsDisplayed().performClick()
            compose.onNodeWithText("https://www.virustotal.com/gui/file/$hash").assertIsDisplayed()
            compose.onNodeWithText("Only the file's fingerprint is sent", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Copy").performClick()
        }
    }

    @Test
    fun noFingerprintMeansNoLink() {
        launch("default").use {
            open("Loom Reader")
            assertEquals(null, fake.apps.value.single { it.id == "loomreader" }.verification?.fileSha256)
            compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToNode(hasText("Checks"))
            assertEquals(0, compose.onAllNodes(hasTestTag(VIRUSTOTAL_TAG)).fetchSemanticsNodes().size)
        }
    }
}
