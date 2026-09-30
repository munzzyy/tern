package io.github.munzzyy.tern.ui.apps

import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.ui.testRow
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
        assertEquals("M", io.github.munzzyy.tern.ui.text.avatarLetter(named[0].config.name))
    }

    private fun names(sections: AppSections) = sections.updates.map { it.config.name } + sections.others.map { it.config.name }

    @Test
    fun everyOrderHasAReverseAndTiesGoByName() {
        val byAuthor = listOf(
            testRow("x", "Xylo").let { it.copy(config = it.config.copy(author = "Beta")) },
            testRow("y", "Yarrow").let { it.copy(config = it.config.copy(author = "Alpha")) },
            testRow("z", "Zinnia").let { it.copy(config = it.config.copy(author = "Alpha")) },
        )
        assertEquals(listOf("Yarrow", "Zinnia", "Xylo"), names(arrange(byAuthor, ListQuery(sort = AppSort.AUTHOR), Locale.US)))
        assertEquals(listOf("Xylo", "Yarrow", "Zinnia"), names(arrange(byAuthor, ListQuery(sort = AppSort.AUTHOR, descending = true), Locale.US)))
        assertEquals(listOf("Zinnia", "Yarrow", "Xylo"), names(arrange(byAuthor, ListQuery(descending = true), Locale.US)))
    }

    @Test
    fun appsAddedBeforeTheDateWasKeptComeFirstWhenOrderedByDate() {
        val added = listOf(
            testRow("n", "New").copy(addedAtMs = 30),
            testRow("o", "Old").copy(addedAtMs = 10),
            testRow("u", "Unknown"),
        )
        assertEquals(listOf("Unknown", "Old", "New"), names(arrange(added, ListQuery(sort = AppSort.ADDED), Locale.US)))
    }

    @Test
    fun theNewestReleaseIsLastWhenOrderedByReleaseDate() {
        val released = listOf("Late" to 300L, "Early" to 100L, "Middle" to 200L).map { (name, at) ->
            testRow(name.lowercase(), name).let { it.copy(latest = Release(id = name, version = "1", publishedAtMs = at)) }
        }
        assertEquals(listOf("Early", "Middle", "Late"), names(arrange(released, ListQuery(sort = AppSort.RELEASED), Locale.US)))
    }

    @Test
    fun favouritesComeFirstAndAppsNotInstalledLastWhenAsked() {
        val rows = listOf(
            testRow("a", "Aster"),
            testRow("b", "Basil", AppStatus.NOT_INSTALLED, installed = null),
            testRow("c", "Clover").let { it.copy(config = it.config.copy(favorite = true)) },
        )
        assertEquals(listOf("Clover", "Aster", "Basil"), names(arrange(rows, ListQuery(buryNotInstalled = true), Locale.US)))
        assertEquals(listOf("Clover", "Aster", "Basil"), names(arrange(rows, ListQuery(), Locale.US)))
        assertEquals(listOf("Clover", "Basil", "Aster"), names(arrange(rows, ListQuery(descending = true), Locale.US)))
    }

    @Test
    fun updatesStayInTheOrderWhenTheyAreNotAskedFirst() {
        val s = arrange(rows, ListQuery(updatesFirst = false), Locale.US)
        assertTrue(s.updates.isEmpty())
        assertEquals(listOf("alder", "Birch", "Cedar", "Dune", "Ember"), s.others.map { it.config.name })
    }

    @Test
    fun groupsByCategoryShowAnAppUnderEachOfItsCategoriesAndTheRestLast() {
        val s = arrange(rows, ListQuery(grouping = AppGrouping.CATEGORY, updatesFirst = false), Locale.US)
        assertEquals(listOf("Maps", "Tools", null), s.groups.map { it.title })
        assertEquals(listOf("Cedar"), s.groups[0].rows.map { it.config.name })
        assertEquals(listOf("Birch", "Cedar"), s.groups[1].rows.map { it.config.name })
        assertEquals(listOf("alder", "Dune", "Ember"), s.groups[2].rows.map { it.config.name })
        // With updates on top, only what is left is grouped.
        assertEquals(listOf("Maps", "Tools"), arrange(rows, ListQuery(grouping = AppGrouping.CATEGORY), Locale.US).groups.map { it.title })
    }

    @Test
    fun groupsBySourceAreNamedAsPeopleKnowThem() {
        val s = arrange(rows, ListQuery(grouping = AppGrouping.SOURCE, updatesFirst = false), Locale.US)
        assertEquals(listOf("GitHub", "GitLab"), s.groups.map { it.title })
        assertEquals(listOf("Cedar"), s.groups[1].rows.map { it.config.name })
    }

    @Test
    fun everyWordTypedHasToBeFoundSomewhere() {
        assertEquals(listOf("Cedar"), names(arrange(rows, ListQuery(text = "cedar gitlab"), Locale.US)))
        assertTrue(arrange(rows, ListQuery(text = "cedar github"), Locale.US).isEmpty)
    }

    @Test
    fun theNewFiltersNarrowAsTheySay() {
        assertEquals(listOf("Dune"), names(arrange(rows, ListQuery(filter = AppFilter.TrackOnly), Locale.US)))
        assertEquals(listOf("Cedar"), names(arrange(rows, ListQuery(filter = AppFilter.Source("gitlab")), Locale.US)))
        assertTrue(arrange(rows, ListQuery(filter = AppFilter.Favorites), Locale.US).isEmpty)
    }

    @Test
    fun updateAllCountsOnlyWhatCanStartNow() {
        assertEquals(1, updatableCount(rows))
    }
}
