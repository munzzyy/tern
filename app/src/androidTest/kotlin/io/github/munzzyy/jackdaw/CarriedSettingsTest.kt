package io.github.munzzyy.jackdaw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.fake.FakeLinks
import io.github.munzzyy.jackdaw.fake.fakeHash
import io.github.munzzyy.jackdaw.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.jackdaw.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.jackdaw.ui.add.ADD_FIND_TAG
import io.github.munzzyy.jackdaw.ui.add.CARRIED_TAG
import io.github.munzzyy.jackdaw.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.jackdaw.ui.text.breakableFingerprint
import io.github.munzzyy.jackdaw.ui.text.formatFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarriedSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun find(link: String) {
        compose.onNodeWithText("Add").performClick()
        compose.tagged(ADD_FIELD_TAG).performTextReplacement(link)
        compose.tagged(ADD_FIND_TAG).performClick()
    }

    @Test
    fun linkSettingsAreShownBeforeAnythingIsStored() {
        val pin = fakeHash("signer:carried:finch")
        launch("default").use {
            find(FakeLinks.CARRIED)
            compose.waitFor(hasTestTag(CARRIED_TAG))
            compose.onNodeWithText("This link sets").performScrollTo().assertIsDisplayed()
            val sentences = listOf(
                "Updates: Only when I check.", "Test versions count as updates.", "until it is 3 days old.",
                "universal\u201d\u2069 are used.", "debug\u201d\u2069 are skipped.", "whose tag matches", "whose title matches",
                "whose notes match", "read from the tag with",
            )
            for (words in sentences) {
                compose.onNodeWithText(words, substring = true).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("Someone other than you chose it", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(breakableFingerprint(formatFingerprint(pin))).performScrollTo().assertIsDisplayed()
            assertTrue("nothing stored before Add", fake.apps.value.none { it.config.name == "Finch" })

            compose.tagged(ADD_CONFIRM_TAG).performScrollTo().performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Install"))
            val stored = fake.apps.value.single { it.config.name == "Finch" }.config
            assertEquals(listOf(pin), stored.pinnedSigners)
            assertEquals(UpdateMode.MANUAL, stored.updates)
            assertEquals("universal", stored.assets.include)
            assertFalse(stored.trackOnly)
        }
    }

    @Test
    fun aPlainLinkShowsNoLinkSettings() {
        launch("default").use {
            find(FakeLinks.NEW_APP)
            compose.waitForText("Sparrow")
            compose.tagged(ADD_CONFIRM_TAG).performScrollTo()
            assertEquals(0, compose.textCount("This link sets"))
        }
    }
}
