package io.github.munzzyy.stamp.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.source.SourceOptions
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.data.AppState
import io.github.munzzyy.stamp.data.StoredApp
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A successful check that lists nothing is a finding about the app, not a failed check. */
@RunWith(AndroidJUnit4::class)
class ListingTest {
    private fun rowFor(h: Harness, type: String, url: String, options: Map<String, String>, state: AppState): Pair<AppStatus, ProblemKind?> {
        val config = AppConfig(id = type, source = SourceSpec(type, url, options), name = "Listing", packageName = PKG)
        h.engine.store.putApp(config, state)
        h.engine.stored[config.id] = StoredApp(config, state)
        h.engine.checks.reevaluate(config.id, network = false)
        h.engine.publish()
        val row = h.row(config.id)
        return row.status to row.problem?.kind
    }

    @Test
    fun anEmptyRepositoryListingSaysNothingFitsThisDevice() {
        Harness("listing").use { h ->
            val repo = mapOf(SourceOptions.PACKAGE to PKG)
            assertEquals(
                AppStatus.ERROR to ProblemKind.NO_FILE_FOR_DEVICE,
                rowFor(h, SourceTypes.FDROID_REPO, "https://example.org/fdroid/repo", repo, AppState(lastCheckedMs = 1)),
            )
            assertEquals(
                AppStatus.ERROR to ProblemKind.NO_RELEASES,
                rowFor(h, SourceTypes.FORGEJO, FakeForge.PROJECT, emptyMap(), AppState(lastCheckedMs = 1)),
            )
            assertEquals(AppStatus.UNKNOWN to null, rowFor(h, SourceTypes.GITHUB, "https://github.com/example/app", emptyMap(), AppState()))
        }
    }
}
