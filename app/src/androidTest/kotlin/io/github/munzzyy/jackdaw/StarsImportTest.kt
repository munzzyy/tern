package io.github.munzzyy.jackdaw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.ui.importing.IMPORT_LIST_TAG
import io.github.munzzyy.jackdaw.ui.importing.STARS_ADD_TAG
import io.github.munzzyy.jackdaw.ui.importing.STARS_SHOW_TAG
import io.github.munzzyy.jackdaw.ui.importing.STARS_USER_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarsImportTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun lookUp(user: String) {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Import apps").performScrollTo().performClick()
        compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasTestTag(STARS_USER_TAG))
        compose.tagged(STARS_USER_TAG).performTextReplacement(user)
        compose.tagged(STARS_SHOW_TAG).performClick()
    }

    private fun tick(name: String) {
        compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
    }

    @Test
    fun onlyTickedRepositoriesAreAddedAndTheRestAreExplained() {
        launch("default").use {
            val before = fake.apps.value.size
            lookUp("example")
            compose.waitForText("example has starred 8 repositories")
            compose.onAllNodes(isToggleable()).assertAll(isOff())
            assertEquals(before, fake.apps.value.size)

            for (name in listOf("example/sparrow", "example/trailmap", "example/missing", "example/tool-1", "example/tool-2", "example/tool-3", "example/tool-4")) tick(name)
            compose.tagged(STARS_ADD_TAG).assertIsDisplayed().assertTextContains("Add 7 selected")
            compose.tagged(STARS_ADD_TAG).performClick()
            compose.waitForText("Import finished", 20_000)

            for (line in listOf("1 app added", "1 was already in your list", "5 could not be brought over:", "Nothing in its releases can be installed on this device.", "Jackdaw could not find it.")) {
                compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasText(line, substring = true))
                compose.onNodeWithText(line, substring = true).assertIsDisplayed()
            }
            assertEquals(1, compose.textCount("example/missing"))
            assertEquals(0, compose.textCount("example/tool-1"))
            compose.onNodeWithText("Show all 4 names").performClick()
            compose.onNodeWithText("example/tool-1", substring = true).assertIsDisplayed()

            val added = fake.apps.value.filter { it.config.source.url.endsWith("/sparrow") }
            assertEquals(1, added.size)
            assertNull(added.single().installed)
            assertTrue(fake.apps.value.none { it.config.source.url.contains("/tool-") })
            assertEquals(before + 1, fake.apps.value.size)
        }
    }

    @Test
    fun aMissingUserIsSaidPlainly() {
        launch("default").use {
            lookUp("nobody")
            compose.waitForText("GitHub has no user named nobody.")
            compose.tagged(STARS_USER_TAG).assertIsDisplayed()
        }
    }
}
