package io.github.munzzyy.stamp.ui.apps

import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.Phase
import io.github.munzzyy.stamp.engine.Progress
import io.github.munzzyy.stamp.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppListTest {
    private val rows = listOf(
        testRow("b", "Birch", AppStatus.UP_TO_DATE, categories = listOf("Tools"), lastChecked = 5),
        testRow("a", "alder", AppStatus.UPDATE_AVAILABLE, installed = "1", offered = "2", lastChecked = 9),
        testRow("c", "Cedar", AppStatus.NOT_INSTALLED, installed = null, categories = listOf(" Tools ", "Maps"), type = "gitlab"),
        testRow("d", "Dune", AppStatus.NEW_RELEASE, trackOnly = true),
        testRow("e", "Ember", AppStatus.UPDATE_AVAILABLE, installed = "1", offered = "2", progress = Progress(Phase.DOWNLOADING, 1, 2)),
    )

    @Test
    fun updatesComeFirstThenTheRestByName() {
        val s = arrange(rows, ListQuery(), Locale.US)
        assertEquals(listOf("alder", "Dune", "Ember"), s.updates.map { it.config.name })
        assertEquals(listOf("Birch", "Cedar"), s.others.map { it.config.name })
    }

    @Test
    fun searchMatchesNamePackageAndUrl() {
        assertEquals(listOf("Cedar"), arrange(rows, ListQuery(text = "CED"), Locale.US).others.map { it.config.name })
        assertEquals(1, arrange(rows, ListQuery(text = "org.example.d"), Locale.US).updates.size)
        assertEquals(5, arrange(rows, ListQuery(text = "example.org"), Locale.US).let { it.updates.size + it.others.size })
        assertTrue(arrange(rows, ListQuery(text = "nothing"), Locale.US).isEmpty)
    }

    @Test
    fun filtersNarrowTheList() {
        val notInstalled = arrange(rows, ListQuery(filter = AppFilter.NotInstalled), Locale.US)
        assertEquals(listOf("Cedar"), notInstalled.others.map { it.config.name })
        assertTrue(notInstalled.updates.isEmpty())
        assertEquals(listOf("Cedar"), arrange(rows, ListQuery(filter = AppFilter.Category("Maps")), Locale.US).others.map { it.config.name })
        assertEquals(2, arrange(rows, ListQuery(filter = AppFilter.Category("Tools")), Locale.US).others.size)
        assertTrue(arrange(rows, ListQuery(filter = AppFilter.Updates), Locale.US).others.isEmpty())
    }

    @Test
    fun categoriesAreTrimmedDistinctAndSorted() {
        assertEquals(listOf("Maps", "Tools"), categoriesOf(rows, Locale.US))
    }

    @Test
    fun sortByRecentlyChecked() {
        val s = arrange(rows, ListQuery(sort = AppSort.RECENTLY_CHECKED), Locale.US)
        assertEquals("alder", s.updates.first().config.name)
        assertEquals("Birch", s.others.first().config.name)
    }

    @Test
    fun theDisplayedNameDrivesSortAndSearch() {
        val named = listOf(testRow("magpie", "Magpie"), testRow("b", "birch"), testRow("z", "Zeta"))
        assertEquals(listOf("birch", "Magpie", "Zeta"), arrange(named, ListQuery(), Locale.US).others.map { it.config.name })
        assertEquals(listOf("Magpie"), arrange(named, ListQuery(text = "magp"), Locale.US).others.map { it.config.name })
        assertEquals("M", io.github.munzzyy.stamp.ui.text.avatarLetter(named[0].config.name))
    }

    @Test
    fun updateAllCountsOnlyWhatCanStartNow() {
        assertEquals(1, updatableCount(rows))
    }
}
