package io.github.munzzyy.jackdaw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.ui.apps.APP_LIST_TAG
import io.github.munzzyy.jackdaw.ui.apps.ListQuery
import io.github.munzzyy.jackdaw.ui.apps.arrange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManyAppsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun threeHundredAppsScrollToTheLastOne() {
        launch("many").use {
            val rows = fake.apps.value
            assertEquals(300, rows.size)
            val sections = arrange(rows, ListQuery())
            val last = (sections.updates + sections.others).last().config.name
            val middle = sections.others[sections.others.size / 2].config.name

            compose.tagged(APP_LIST_TAG).performScrollToNode(hasContentDescription("$middle.", substring = true))
            compose.row(middle).assertIsDisplayed()
            compose.tagged(APP_LIST_TAG).performScrollToNode(hasContentDescription("$last.", substring = true))
            compose.row(last).assertIsDisplayed()
        }
    }
}
