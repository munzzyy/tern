package io.github.munzzyy.jackdaw.engine

import io.github.munzzyy.jackdaw.core.engine.InstallRecord
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.NotesFormat
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.data.FileFacts
import io.github.munzzyy.jackdaw.data.GateBlock
import io.github.munzzyy.jackdaw.data.PatternProblem
import io.github.munzzyy.jackdaw.data.PendingInstall
import io.github.munzzyy.jackdaw.data.StateJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateJsonTest {
    private val release = Release(
        id = "v2", version = "2.0", versionCode = 2, title = "Two", notes = "n", notesFormat = NotesFormat.HTML,
        publishedAtMs = 10, prerelease = true, pageUrl = "https://example.org/r",
        assets = listOf(
            Asset("app.apk", "https://example.org/app.apk", 5, "ab".repeat(32), needsAuth = true, signers = listOf("cd".repeat(32))),
            Asset("SHA256SUMS", "https://example.org/SHA256SUMS", kind = AssetKind.CHECKSUM),
        ),
    )

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val state = AppState(
            releases = listOf(release),
            lastCheckedMs = 1,
            checkProblem = Problem(ProblemKind.RATE_LIMITED, "wait", 99),
            record = InstallRecord("v1", "1.0", 1, "ef".repeat(32), 4),
            pending = PendingInstall(3, "com.example.app", "v2", "2.0", 2, null, 5, "https://example.org/app.apk", 7, waitingForUser = true),
            block = GateBlock("v2", "https://example.org/app.apk", Problem(ProblemKind.CHECKSUM_MISMATCH, "bad")),
            installProblem = Problem(ProblemKind.DOWNGRADE, "older"),
            seenReleaseId = "v2",
            movedTo = "https://example.org/moved",
            patternProblem = PatternProblem("a\u0000b", "slow"),
            description = "d",
            announcedReleaseId = "v2",
        )
        assertEquals(state, StateJson.decode(StateJson.encode(state)))
    }

    @Test
    fun notesAndReleasesAreCapped() {
        val long = release.copy(notes = "x".repeat(StateJson.MAX_NOTES + 5))
        val back = StateJson.decode(StateJson.encode(AppState(releases = List(StateJson.MAX_RELEASES + 3) { long.copy(id = "r$it") })))
        assertEquals(StateJson.MAX_RELEASES, back.releases.size)
        assertEquals(StateJson.MAX_NOTES, back.releases.first().notes!!.length)
    }

    @Test
    fun unreadableStorageBecomesAnEmptyStateNotACrash() {
        for (text in listOf(null, "", "{", "[]", "{\"releases\":7}", "{\"record\":{\"releaseId\":1}}", "\u0000")) {
            val state = StateJson.decode(text)
            assertNull(state.record)
        }
        assertEquals(AppState(), StateJson.decode("not json"))
    }

    @Test
    fun factsRoundTripAndRejectTheirAbsence() {
        val facts = FileFacts("com.example.app", 3, "3.0", listOf("aa"), listOf("bb"), listOf("android.permission.CAMERA"), 24, 35, false, true, "GitHub release digest")
        assertEquals(facts, StateJson.decodeFacts(StateJson.encodeFacts(facts)))
        assertNull(StateJson.decodeFacts("{\"versionCode\":3}"))
        assertNull(StateJson.decodeFacts("garbage"))
    }
}
