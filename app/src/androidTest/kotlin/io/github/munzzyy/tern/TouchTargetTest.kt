package io.github.munzzyy.tern

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.Density
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.apps.GroupHeader
import io.github.munzzyy.tern.ui.common.pressRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What a finger presses is never under 48 dp, even where it looks smaller. */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aGroupHeaderIsATouchTargetAtTheTightestDensity() {
        runBlocking { fake.saveSettings(Settings(density = Density.COMPACT)) }
        try {
            compose.host { GroupHeader("Games", 3, folded = false, dot = null, focus = Modifier) {} }
            compose.onNode(hasText("Games")).assertHeightIsAtLeast(48.dp)
        } finally {
            runBlocking { fake.saveSettings(Settings()) }
        }
    }

    @Test
    fun aSmallLinkTakesPressesOverATouchTargetWithoutMakingItsLineTaller() {
        var row = 0
        var link = 0
        compose.host {
            Column(Modifier.clickable { row++ }) {
                Text("Status of the app")
                Row(Modifier.testTag("line")) {
                    Text("changes", Modifier.pressRoom(48.dp).clickable { link++ }.wrapContentSize())
                }
            }
        }
        val line = compose.onNodeWithTag("line", useUnmergedTree = true).getUnclippedBoundsInRoot().height
        assertTrue("the line keeps the height of its text, not $line", line < 48.dp)
        val target = compose.onNodeWithText("changes")
        target.assertHeightIsAtLeast(48.dp)
        target.performTouchInput { click(Offset(centerX, 2f)) }
        target.performTouchInput { click(Offset(centerX, bottom - 2f)) }
        compose.waitForIdle()
        assertEquals("both near misses land on the link", 2, link)
        assertEquals(0, row)
    }
}
