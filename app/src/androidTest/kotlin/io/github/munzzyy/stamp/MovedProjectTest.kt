package io.github.munzzyy.stamp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.fake.FakeLinks
import io.github.munzzyy.stamp.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.stamp.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.stamp.ui.detail.MOVED_FOLLOW_TAG
import io.github.munzzyy.stamp.ui.detail.MOVED_KEEP_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovedProjectTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun open(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
        compose.onNodeWithTag(DETAIL_LIST_TAG).performScrollToNode(hasTestTag(MOVED_FOLLOW_TAG))
    }

    @Test
    fun followingTheSameAppSwitchesTheSource() {
        launch("default").use {
            open("Harbor Terminal")
            compose.onNodeWithText("This project moved to ${FakeLinks.MOVED_HOME}").assertIsDisplayed()
            compose.tagged(MOVED_FOLLOW_TAG).performClick()
            compose.waitForText("Stamp now follows the new address.")
            assertEquals(0, compose.textCount("This project moved to ${FakeLinks.MOVED_HOME}"))
            assertEquals(FakeLinks.MOVED_HOME, fake.apps.value.single { it.id == "harborterm" }.config.source.url)
        }
    }

    @Test
    fun aRefusalSaysWhyAndTheOldAddressCanBeKept() {
        launch("default").use {
            open("Pocket Notes")
            val before = fake.apps.value.single { it.id == "pocketnotes" }.config.source
            compose.tagged(MOVED_FOLLOW_TAG).performClick()
            compose.waitForText("signed by someone else")
            assertEquals(before, fake.apps.value.single { it.id == "pocketnotes" }.config.source)
            compose.tagged(MOVED_KEEP_TAG).performClick()
            compose.waitUntil(5_000) { compose.textCount("This project moved to ${FakeLinks.MOVED_ELSEWHERE}") == 0 }
            assertEquals(null, fake.apps.value.single { it.id == "pocketnotes" }.movedTo)
            assertEquals(before, fake.apps.value.single { it.id == "pocketnotes" }.config.source)
        }
    }
}
