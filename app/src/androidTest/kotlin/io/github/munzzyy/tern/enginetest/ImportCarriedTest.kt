package io.github.munzzyy.tern.enginetest

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCarriedTest {
    private fun app(id: String, name: String) = AppConfig(id, SourceSpec(SourceTypes.GITHUB, "https://github.com/example/$id"), name)

    @Test
    fun anImportNamesTheAppsThatBroughtPinsOrFilters() = runBlocking {
        val configs = listOf(
            app("pinned", "Pinned").copy(pinnedSigners = listOf("ab".repeat(32))),
            app("filtered", "Filtered").copy(releases = ReleasePolicy(titleFilter = "stable")),
            app("both", "Both").copy(pinnedSigners = listOf("cd".repeat(32)), assets = AssetPolicy(exclude = "debug")),
            app("plain", "Plain"),
            app("blank", "Blank").copy(releases = ReleasePolicy(tagFilter = "")),
        )
        val file = File(targetContext.cacheDir, "carried-export.json")
        file.writeText(TernExport.write(configs, 0, "test"))
        Harness("carried").use { h ->
            val summary = h.engine.importFrom(Uri.fromFile(file))
            assertEquals(5, summary.added)
            assertEquals(listOf("Pinned", "Both"), summary.withPins)
            assertEquals(listOf("Filtered", "Both"), summary.withFilters)

            val again = h.engine.importFrom(Uri.fromFile(file))
            assertEquals(5, again.alreadyPresent)
            assertEquals(emptyList<String>(), again.withPins)
            assertEquals(emptyList<String>(), again.withFilters)
        }
        assertEquals(true, file.delete())
    }
}
