package io.github.munzzyy.tern.ui.apps

import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.engine.UpdateAllMode
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
        val notInstalled = arrange(rows, ListQuery(filter = ListFilter(installed = false)), Locale.US)
        assertEquals(listOf("Cedar"), notInstalled.others.map { it.config.name })
        assertTrue(notInstalled.updates.isEmpty())
        assertEquals(listOf("Cedar"), arrange(rows, ListQuery(filter = ListFilter(categories = setOf("Maps"))), Locale.US).others.map { it.config.name })
        assertEquals(2, arrange(rows, ListQuery(filter = ListFilter(categories = setOf("Tools"))), Locale.US).others.size)
        assertTrue(arrange(rows, ListQuery(filter = ListFilter(updates = true)), Locale.US).others.isEmpty())
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
    fun focusLandsOnTheFirstAppDrawnOrOnTheHeaderOfAFoldedFirstGroup() {
        val grouped = arrange(rows, ListQuery(grouping = AppGrouping.CATEGORY, updatesFirst = false), Locale.US)
        assertEquals("c:Maps" + "c", firstPlace(grouped, emptySet()))
        assertEquals("g-c:Maps", firstPlace(grouped, setOf("c:Maps")))
        assertEquals("g-c:Maps", firstPlace(grouped, grouped.groups.map { it.key }.toSet()))
        assertEquals("a", firstPlace(arrange(rows, ListQuery(grouping = AppGrouping.CATEGORY), Locale.US), setOf("c:Maps")))
        assertEquals("a", firstPlace(arrange(rows, ListQuery(), Locale.US), emptySet()))
        assertEquals("b", firstPlace(arrange(rows, ListQuery(text = "birch", updatesFirst = false), Locale.US), emptySet()))
        assertEquals(null, firstPlace(AppSections(emptyList(), emptyList()), emptySet()))
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
        assertEquals(listOf("Dune"), names(arrange(rows, ListQuery(filter = ListFilter(tracked = true)), Locale.US)))
        assertEquals(listOf("Cedar"), names(arrange(rows, ListQuery(filter = ListFilter(sources = setOf("gitlab"))), Locale.US)))
        assertTrue(arrange(rows, ListQuery(filter = ListFilter(favorites = true)), Locale.US).isEmpty)
    }

    @Test
    fun groupsStartFoldedOnlyWhenTheSettingAsks() {
        val groups = arrange(rows, ListQuery(grouping = AppGrouping.SOURCE, updatesFirst = false), Locale.US).groups
        assertEquals(setOf("s:x"), foldedGroups(groups, setOf("s:x"), foldedAtStart = false))
        val keys = groups.map { it.key }
        assertEquals(keys.toSet(), foldedGroups(groups, emptySet(), foldedAtStart = true))
        // A group opened since stays open, and the others stay folded.
        assertEquals(keys.drop(1).toSet(), foldedGroups(groups, setOf(keys.first()), foldedAtStart = true))
    }

    @Test
    fun bulkActionsTouchOnlyTheAppsTheyCanChange() {
        val tracked = testRow(id = "t", status = AppStatus.NEW_RELEASE, trackOnly = true)
        val installed = testRow(id = "i", status = AppStatus.UP_TO_DATE)
        val noRelease = testRow(id = "n", offered = null)
        val noneRanked = testRow(id = "u", withFile = false)
        assertEquals(listOf("t"), touched(BulkAction.MARK_SEEN, listOf(tracked, installed)).map { it.id })
        // Any file of a release can be saved, also where none is one Tern would install.
        assertEquals(listOf("t", "i", "u"), touched(BulkAction.SAVE_FILES, listOf(tracked, installed, noRelease, noneRanked)).map { it.id })
    }

    @Test
    fun updateAllCountsOnlyWhatCanStartNow() {
        assertEquals(1, updatableCount(rows))
    }

    @Test
    fun filtersOfOneKindWidenAndOfDifferentKindsNarrow() {
        val both = ListFilter(categories = setOf("Maps", "Tools"))
        assertEquals(listOf("Birch", "Cedar"), names(arrange(rows, ListQuery(filter = both, updatesFirst = false), Locale.US)))
        val installedTools = ListFilter(categories = setOf("Tools"), installed = true)
        assertEquals(listOf("Birch"), names(arrange(rows, ListQuery(filter = installedTools), Locale.US)))
        assertEquals(listOf("alder", "Dune", "Ember"), names(arrange(rows, ListQuery(filter = ListFilter(updates = true, problems = true)), Locale.US)))
        assertEquals(listOf("Cedar"), names(arrange(rows, ListQuery(filter = ListFilter(sources = setOf("gitlab"), categories = setOf("Tools"))), Locale.US)))
        assertTrue(arrange(rows, ListQuery(filter = ListFilter(sources = setOf("gitlab"), updates = true)), Locale.US).isEmpty)
    }

    @Test
    fun obtainiumsWordsAndHidesNarrowTheListToo() {
        assertEquals(listOf("Cedar"), names(arrange(rows, ListQuery(filter = ListFilter(name = "ced")), Locale.US)))
        assertEquals(5, arrange(rows, ListQuery(filter = ListFilter(author = "exam")), Locale.US).let { it.updates.size + it.others.size })
        assertTrue(arrange(rows, ListQuery(filter = ListFilter(author = "nobody")), Locale.US).isEmpty)
        assertEquals(listOf("Dune"), names(arrange(rows, ListQuery(filter = ListFilter(packageName = "example.d")), Locale.US)))
        assertEquals(listOf("alder", "Dune", "Ember", "Cedar"), names(arrange(rows, ListQuery(filter = ListFilter(hideUpToDate = true)), Locale.US)))
        assertEquals(listOf("alder", "Ember", "Birch", "Cedar"), names(arrange(rows, ListQuery(filter = ListFilter(tracked = false)), Locale.US)))
    }

    @Test
    fun chipsTurnPartsOfTheFilterOnAndOffAndAllClearsEverything() {
        val tools = ListFilter().toggled(AppFilter.Category("Tools")).toggled(AppFilter.Category("Maps")).toggled(AppFilter.Installed)
        assertTrue(tools.has(AppFilter.Category("Tools")) && tools.has(AppFilter.Category("Maps")) && tools.has(AppFilter.Installed))
        assertTrue(!tools.has(AppFilter.All) && tools.isOn)
        val swapped = tools.toggled(AppFilter.NotInstalled)
        assertTrue(swapped.has(AppFilter.NotInstalled) && !swapped.has(AppFilter.Installed))
        assertEquals(setOf("Maps"), tools.toggled(AppFilter.Category("Tools")).categories)
        assertEquals(ListFilter(), tools.copy(name = "x").toggled(AppFilter.All))
        assertTrue(ListFilter().has(AppFilter.All))
        assertTrue(!ListFilter(name = "  ").isOn)
    }

    @Test
    fun aCategoryOrASourceThatIsGoneLeavesTheFilter() {
        val filter = ListFilter(categories = setOf("Tools", "Gone"), sources = setOf("gitlab", "codeberg"))
        assertEquals(ListFilter(categories = setOf("Tools"), sources = setOf("gitlab")), filter.within(listOf("Tools"), listOf("gitlab", "github")))
    }

    @Test
    fun aProjectThatMovedComesFirst() {
        val moved = testRow("m", "Zinc").copy(movedTo = "https://example.org/new/zinc")
        val favourite = testRow("f", "Fern").let { it.copy(config = it.config.copy(favorite = true)) }
        val s = arrange(listOf(testRow("a", "Aster"), favourite, moved), ListQuery(), Locale.US)
        assertEquals(listOf("Zinc", "Fern", "Aster"), s.others.map { it.config.name })
    }

    @Test
    fun updateAllTakesInWhatTheSettingSaysAndOnlyForTwoOrMore() {
        assertEquals(UpdateAllPlan(3, 0), updateAllPlan(UpdateAllMode.UPDATES, updatable = 3, installable = 4))
        assertEquals(UpdateAllPlan(3, 4), updateAllPlan(UpdateAllMode.ALL, updatable = 3, installable = 4))
        assertEquals(null, updateAllPlan(UpdateAllMode.NONE, updatable = 3, installable = 4))
        assertEquals(null, updateAllPlan(UpdateAllMode.UPDATES, updatable = 1, installable = 4))
        assertEquals(UpdateAllPlan(1, 1), updateAllPlan(UpdateAllMode.ALL, updatable = 1, installable = 1))
        assertEquals(R.string.action_update_all, updateAllLabel(UpdateAllPlan(2, 0)))
        assertEquals(R.string.action_install_all, updateAllLabel(UpdateAllPlan(0, 2)))
        assertEquals(R.string.action_install_update_all, updateAllLabel(UpdateAllPlan(1, 1)))
    }
}
