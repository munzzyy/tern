package io.github.munzzyy.tern

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The note shown once after the first run, over the whole app. */
@RunWith(AndroidJUnit4::class)
class VerificationNoteTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun seen() = noteSeen(true)

    @Test
    fun theLinkInTheNoteAsksBeforeItOpensInsteadOfClosingTern() {
        launch("default").close()
        noteSeen(false)
        ActivityScenario.launch<MainActivity>(Intent(appContext, MainActivity::class.java)).use { scenario ->
            compose.waitForText("Android's developer verification")
            compose.onNodeWithText("Google's page about it").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForText("Open this link?")
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }
}
