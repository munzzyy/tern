package io.github.munzzyy.stamp.ui.apps

import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.Phase
import io.github.munzzyy.stamp.engine.Progress
import io.github.munzzyy.stamp.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListStateTest {
    private val upToDate = testRow("a", "Alder")
    private val update = testRow("b", "Birch", AppStatus.UPDATE_AVAILABLE, installed = "1", offered = "2")
    private val notInstalled = testRow("c", "Cedar", AppStatus.NOT_INSTALLED, installed = null)
    private val waiting = testRow("d", "Dune", AppStatus.UPDATE_AVAILABLE, installed = "1", offered = "2", progress = Progress(Phase.WAITING_FOR_USER))

    @Test
    fun rowsOfOneKindGetNoFilters() {
        assertEquals(emptyList<AppFilter>(), offeredFilters(listOf(upToDate, testRow("z", "Zeta")), emptyList(), AppFilter.All))
        assertEquals(emptyList<AppFilter>(), offeredFilters(emptyList(), emptyList(), AppFilter.All))
    }

    @Test
    fun onlyFiltersThatTellRowsApartAreOffered() {
        assertEquals(listOf(AppFilter.All, AppFilter.Updates), offeredFilters(listOf(upToDate, update), emptyList(), AppFilter.All))
        assertEquals(
            listOf(AppFilter.All, AppFilter.Updates, AppFilter.Installed, AppFilter.NotInstalled),
            offeredFilters(listOf(upToDate, update, notInstalled), emptyList(), AppFilter.All),
        )
    }

    @Test
    fun aCategoryIsOfferedUnlessEveryRowIsInIt() {
        val tools = upToDate.copy(config = upToDate.config.copy(categories = listOf("Tools")))
        val alsoTools = testRow("z", "Zeta", categories = listOf("Tools"))
        assertEquals(listOf(AppFilter.All, AppFilter.Category("Tools")), offeredFilters(listOf(tools, testRow("y", "Yew")), listOf("Tools"), AppFilter.All))
        assertEquals(emptyList<AppFilter>(), offeredFilters(listOf(tools, alsoTools), listOf("Tools"), AppFilter.All))
    }

    @Test
    fun theFilterInUseStaysSoItCanBeLeft() {
        val rows = listOf(upToDate, testRow("z", "Zeta"))
        assertEquals(listOf(AppFilter.All, AppFilter.Updates), offeredFilters(rows, emptyList(), AppFilter.Updates))
    }

    @Test
    fun aRemovedAppThatCanStillBeTakenBackIsLeftOut() {
        val rows = listOf(upToDate, update, notInstalled)
        val state = listState(rows, ListQuery(), gone = setOf("b"))
        assertEquals(2, state.total)
        assertTrue(state.sections.updates.isEmpty())
        assertEquals(listOf("Alder", "Cedar"), state.sections.others.map { it.config.name })
        assertEquals(0, state.updatable)
        assertEquals(3, listState(rows, ListQuery(), gone = emptySet()).total)
    }

    @Test
    fun installsThatWaitAreNamedWhateverTheSearchHides() {
        val rows = listOf(upToDate, waiting, update)
        assertEquals(listOf("Dune"), listState(rows, ListQuery(), emptySet()).waiting.map { it.config.name })
        assertEquals(listOf("Dune"), listState(rows, ListQuery(text = "alder", filter = AppFilter.Installed), emptySet()).waiting.map { it.config.name })
        assertEquals(emptyList<String>(), listState(rows, ListQuery(), setOf("d")).waiting.map { it.config.name })
    }

    @Test
    fun aCategoryThatIsGoneFallsBackToAll() {
        val state = listState(listOf(upToDate, update), ListQuery(filter = AppFilter.Category("Gone")), emptySet())
        assertEquals(2, state.sections.updates.size + state.sections.others.size)
    }

    @Test
    fun theSearchFieldFoldsBelowEightApps() {
        assertTrue(foldsSearch(7))
        assertTrue(!foldsSearch(8))
        assertTrue(foldsSearch(0))
    }

    @Test
    fun onATelevisionTheSearchFieldIsAlwaysFolded() {
        assertTrue(foldsSearch(300, television = true))
        assertTrue(!foldsSearch(300, television = false))
    }

    @Test
    fun theActionStandsBesideTheWordsWhereBothHaveRoom() {
        assertEquals(ActionPlace.BESIDE, actionPlace(360.dp, icon = 40.dp, fontScale = 1f, noTouch = false))
        assertEquals(ActionPlace.BESIDE, actionPlace(411.dp, icon = 40.dp, fontScale = 1.3f, noTouch = false))
        assertEquals(ActionPlace.BESIDE, actionPlace(784.dp, icon = 48.dp, fontScale = 1f, noTouch = true))
    }

    @Test
    fun theActionGoesUnderTheWordsWhereTheListIsNarrowOrTheTextLarge() {
        assertEquals(ActionPlace.UNDER, actionPlace(411.dp, icon = 40.dp, fontScale = 1.5f, noTouch = false))
        assertEquals(ActionPlace.UNDER, actionPlace(320.dp, icon = 40.dp, fontScale = 1f, noTouch = false))
    }

    @Test
    fun withoutATouchScreenANarrowListLeavesTheActionToTheDetail() {
        assertEquals("the list beside the detail on a television", ActionPlace.NOWHERE, actionPlace(352.dp, icon = 48.dp, fontScale = 1f, noTouch = true))
        assertEquals(ActionPlace.NOWHERE, actionPlace(784.dp, icon = 48.dp, fontScale = 2f, noTouch = true))
    }
}
