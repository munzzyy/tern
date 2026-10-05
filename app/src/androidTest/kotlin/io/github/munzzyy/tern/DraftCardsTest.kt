package io.github.munzzyy.tern

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.ui.detail.DETAIL_LIST_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.tern.ui.detail.DraftPart
import io.github.munzzyy.tern.ui.detail.draftSaveTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Save and Discard stand in the card whose fields were changed, and each saves or discards that card alone. */
@RunWith(AndroidJUnit4::class)
class DraftCardsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val nameField = hasSetTextAction() and hasText("Name")
    private val includeField = hasSetTextAction() and hasText("Only files matching")

    private fun open(name: String) {
        compose.shownRow(name).performClick()
        compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG))
    }

    private fun scrollTo(matcher: SemanticsMatcher) = compose.tagged(DETAIL_LIST_TAG).performScrollToNode(matcher)

    private fun saveRows(part: DraftPart): Int = compose.onAllNodes(hasTestTag(draftSaveTag(part))).fetchSemanticsNodes().size

    private fun trailMap() = fake.apps.value.single { it.id == "trailmap" }.config

    @Test
    fun aNameTypedPutsSaveInTheNameCardAlone() {
        launch("default").use {
            open("Trail Map")
            scrollTo(nameField)
            compose.onNode(nameField).performTextReplacement("My map")
            compose.waitFor(hasTestTag(draftSaveTag(DraftPart.NAME)))

            scrollTo(includeField)
            compose.waitForIdle()
            assertEquals("the files card is unchanged and offers no Save", 0, saveRows(DraftPart.FILES))

            scrollTo(hasText("Show filters", substring = true))
            compose.onNode(hasText("Show filters", substring = true)).performClick()
            scrollTo(hasSetTextAction() and hasText("Only tags matching"))
            compose.waitForIdle()
            assertEquals("the advanced card is unchanged and offers no Save", 0, saveRows(DraftPart.ADVANCED))
        }
    }

    @Test
    fun eachCardSavesAndDiscardsOnlyItsOwnFields() {
        launch("default").use {
            open("Trail Map")
            scrollTo(includeField)
            compose.onNode(includeField).performTextReplacement("arm64")
            scrollTo(nameField)
            compose.onNode(nameField).performTextReplacement("My map")

            scrollTo(hasTestTag(draftSaveTag(DraftPart.NAME)))
            compose.onNode(hasText("Save") and hasAnyAncestor(hasTestTag(draftSaveTag(DraftPart.NAME)))).performClick()
            compose.waitUntil(3_000) { trailMap().customName == "My map" }
            assertNull("the include typed in the files card waits for its own Save", trailMap().assets.include)

            scrollTo(hasTestTag(draftSaveTag(DraftPart.FILES)))
            compose.onNode(hasText("Discard") and hasAnyAncestor(hasTestTag(draftSaveTag(DraftPart.FILES)))).performClick()
            compose.waitUntil(3_000) { saveRows(DraftPart.FILES) == 0 }
            assertNull(trailMap().assets.include)
            assertEquals("My map", trailMap().customName)
        }
    }
}
