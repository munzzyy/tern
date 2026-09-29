package io.github.munzzyy.stamp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.ui.LocalActionScope
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.common.LocalNoTouch
import io.github.munzzyy.stamp.ui.firstrun.FIRST_RUN_ADD_TAG
import io.github.munzzyy.stamp.ui.firstrun.FIRST_RUN_NOTIFY_TAG
import io.github.munzzyy.stamp.ui.firstrun.FirstRunScreen
import io.github.munzzyy.stamp.ui.theme.StampTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first run by itself, drawn as a phone and as a television draw it. */
@RunWith(AndroidJUnit4::class)
class FirstRunScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val pressed = mutableListOf<String>()

    private fun show(television: Boolean, mayNotify: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(
                LocalEngine provides fake,
                LocalActionScope provides rememberCoroutineScope(),
                LocalNoTouch provides television,
            ) {
                StampTheme(Settings(), television = television) {
                    FirstRunScreen(
                        onAddFirst = { pressed += "add" },
                        onSkip = { pressed += "skip" },
                        onWellKnown = { pressed += "known" },
                        mayNotify = mayNotify,
                    )
                }
            }
        }
    }

    private fun questions(): Int = compose.onAllNodes(hasTestTag(FIRST_RUN_NOTIFY_TAG)).fetchSemanticsNodes().size

    @Test
    fun aTelevisionIsNotAskedAboutNotifications() {
        show(television = true)
        compose.tagged(FIRST_RUN_ADD_TAG).assertIsDisplayed()
        assertEquals(0, questions())
        assertEquals(0, compose.textCount("Allow notifications"))
    }

    @Test
    fun aPhoneIsAskedWhereAndroidWantsToBeAsked() {
        assumeTrue("Android asks from version 13 on", Build.VERSION.SDK_INT >= 33)
        show(television = false)
        compose.tagged(FIRST_RUN_NOTIFY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertIsDisplayed()
    }

    @Test
    fun aPhoneThatMayNotifyAlreadyIsNotAskedAgain() {
        show(television = false, mayNotify = true)
        compose.tagged(FIRST_RUN_ADD_TAG).assertIsDisplayed()
        assertEquals(0, questions())
    }

    @Test
    fun itSaysWhatStampDoesAndOffersThreeWaysOn() {
        show(television = true)
        for (sentence in listOf(
            "Stamp installs and updates apps straight from where their developers publish them.",
            "Before Android sees a file, Stamp checks who signed it and whether it matches the publisher's checksum.",
            "Everything it finds and does is written down in plain words.",
        )) {
            compose.onNodeWithText(sentence).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Add your first app").performClick()
        compose.onNodeWithText("Well known apps").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("add", "known", "skip"), pressed)
    }
}
