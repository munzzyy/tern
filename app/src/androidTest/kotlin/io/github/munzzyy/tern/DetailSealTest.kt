package io.github.munzzyy.tern

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.common.EXPLAIN_PACKAGE_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_SEAL_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The seal stands on the Checks card of a file that has passed every check, and on no other. */
@RunWith(AndroidJUnit4::class)
class DetailSealTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun open(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
    }

    private fun sealsOnTheChecks(): Int {
        compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(EXPLAIN_PACKAGE_TAG))
        compose.waitForIdle()
        return compose.onAllNodes(hasTestTag(DETAIL_SEAL_TAG)).fetchSemanticsNodes().size
    }

    @Test
    fun theSealComesOnceTheDownloadedFileHasPassed() {
        launch("default").use {
            open("Trail Map")
            assertEquals("a file that was only read from its header carries no seal", 0, sealsOnTheChecks())
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(DETAIL_PRIMARY_TAG))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitUntil(20_000) { fake.apps.value.single { it.id == "trailmap" }.status == AppStatus.UP_TO_DATE }
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(DETAIL_SEAL_TAG))
            compose.tagged(DETAIL_SEAL_TAG).assertIsDisplayed()
            compose.onNode(hasText("This file passed every check") and hasAnyAncestor(hasTestTag(DETAIL_SEAL_TAG))).assertIsDisplayed()
        }
    }

    @Test
    fun aBlockedFileNeverCarriesTheSeal() {
        launch("default").use {
            open("Lantern PDF")
            assertEquals(0, sealsOnTheChecks())
            assertEquals(0, compose.textCount("This file passed every check"))
        }
    }

    @Test
    fun whileAnInstallWaitsTheScreenSaysThatAndroidMayAskAgain() {
        launch("default").use {
            open("Brush Draw")
            compose.onNodeWithText("Android, or Play Protect, may ask once more. That is their own check and not a problem with the file.").assertIsDisplayed()
            compose.tagged(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(DETAIL_PRIMARY_TAG))
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5_000) { fake.apps.value.single { it.id == "brushdraw" }.progress == null }
            compose.waitUntil(5_000) { compose.textCount("Play Protect", substring = true) == 0 }
        }
    }
}
