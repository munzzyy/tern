package io.github.munzzyy.tern.ui.common

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.ui.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveFilePickTest {
    private val apk = Asset("app.apk", "https://example.org/app.apk")
    private val sums = Asset("SHA256SUMS", "https://example.org/SHA256SUMS")
    private val source = Asset("app-v1.zip", "https://example.org/archive/v1.zip")
    private val release = Release(id = "v1", version = "1", assets = listOf(apk, sums), sourceArchives = listOf(source))

    @Test
    fun everyFileOfTheReleaseCanBePickedTheSourceAmongThem() {
        assertEquals(listOf(apk, sums, source), release.savable)
    }

    @Test
    fun thePickerStartsOnTheFileTernWouldInstallElseTheFirst() {
        assertEquals(sums.url, startingPick(release.savable, sums.url))
        assertEquals(source.url, startingPick(release.savable, source.url))
        assertEquals(apk.url, startingPick(release.savable, "https://example.org/gone.apk"))
        assertEquals(apk.url, startingPick(release.savable, null))
        assertNull(startingPick(emptyList(), apk.url))
    }

    @Test
    fun theProblemsShownAreThoseTernHoldsNowForTheAppsNamed() {
        val broken = testRow(id = "b", name = "Broken", problem = Problem(ProblemKind.NETWORK, "The server did not answer."))
        val fine = testRow(id = "f", name = "Fine")
        val shown = problemsOf(listOf(fine, broken), listOf("b", "f", "gone", "b"))
        assertEquals(listOf("b" to "The server did not answer."), shown.map { (row, reason) -> row.id to reason })
    }
}
