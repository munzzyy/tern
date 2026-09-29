package io.github.munzzyy.jackdaw

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.fake.FakeLinks
import io.github.munzzyy.jackdaw.ui.add.ADD_FIELD_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingIntentTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun sharedTextPrefillsAddWithItsFirstHttpsLink() {
        val share = mainIntent("default")
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Found this http://plain.example.org and ${FakeLinks.NEW_APP}, have a look")
        launch("default", share).use {
            compose.tagged(ADD_FIELD_TAG).assertTextContains(FakeLinks.NEW_APP)
            compose.waitForText("Sparrow")
            assertTrue("a share never adds by itself", fake.apps.value.none { it.id == "sparrow" })
        }
    }

    @Test
    fun jackdawLinkPrefillsAddWithTheDecodedUrl() {
        val encoded = Uri.encode(FakeLinks.WARNED_APP)
        val view = mainIntent("default").setAction(Intent.ACTION_VIEW).setData(Uri.parse("jackdaw://add?url=$encoded"))
        launch("default", view).use {
            compose.tagged(ADD_FIELD_TAG).assertTextContains(FakeLinks.WARNED_APP)
            compose.waitForText("Wren")
            assertTrue(fake.apps.value.none { it.id == "wren" })
        }
    }

    @Test
    fun linkThatIsNotHttpsLeavesAddAlone() {
        val view = mainIntent("default").setAction(Intent.ACTION_VIEW).setData(Uri.parse("jackdaw://add?url=http%3A%2F%2Fexample.org"))
        launch("default", view).use {
            compose.waitForText("Search your apps")
            assertTrue(compose.textCount("Add an app") == 0)
        }
    }

    @Test
    fun hugeShareIsCappedBeforeItReachesTheField() {
        val share = mainIntent("default")
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "word ".repeat(5_000))
        launch("default", share).use {
            compose.waitForText("Add an app")
            val field = compose.tagged(ADD_FIELD_TAG).fetchSemanticsNode()
            val text = field.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            assertTrue("field holds ${text.length} chars", text.length in 1..8_000)
        }
    }
}
