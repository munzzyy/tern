package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.interop.TernExport
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The link door through the whole engine, with the sentences as they are in the resources. */
@RunWith(AndroidJUnit4::class)
class LinkDoorTest {
    private fun app(name: String) = AppConfig(name.lowercase(), SourceSpec(SourceTypes.GITHUB, "https://github.com/example/${name.lowercase()}"), name)

    private suspend fun problemOf(call: suspend () -> Any?): Problem {
        val answer = try {
            call()
        } catch (e: ProblemException) {
            return e.problem
        }
        throw AssertionError("was not refused, and answered $answer")
    }

    @Test
    fun anExportAtAnHttpsAddressIsImportedAndNoTokenGoesOut() = runBlocking {
        val routes = Routes(FakeForge()).json(EXPORT, TernExport.write(listOf(app("Wren"), app("Dunnock")), 0, "test"))
        Harness("link-door", http = routes).use { h ->
            h.engine.setToken("files.test", "a-token-for-this-host")
            val summary = h.engine.importFromLink(EXPORT)
            assertEquals(2, summary.added)
            assertEquals(listOf("Dunnock", "Wren"), h.engine.apps.value.map { it.config.name })
            assertNull(routes.requests.single { it.url == EXPORT }.authorization)
            assertEquals(2, h.engine.importFromLink(EXPORT).alreadyPresent)
        }
    }

    @Test
    fun whatCannotBeImportedIsSaidInASentence() = runBlocking {
        val routes = Routes(FakeForge()).on(PAGE) { HttpResponse.of(200, "<html></html>", url = it.url) }
        Harness("link-refused", http = routes).use { h ->
            assertEquals(
                Problem(ProblemKind.UNSUPPORTED, "Only addresses that start with https:// are accepted."),
                problemOf { h.engine.importFromLink("http://files.test/tern-apps.json") },
            )
            assertEquals(
                Problem(ProblemKind.NOT_FOUND, "There is no file at that address. Check it for typing mistakes."),
                problemOf { h.engine.importFromLink("https://forge.test/no-such-file.json") },
            )
            assertEquals(ProblemKind.PARSE, problemOf { h.engine.importFromLink(PAGE) }.kind)
            assertEquals(emptyList<String>(), h.engine.apps.value.map { it.config.name })
            assertEquals(emptyList<String>(), routes.requests.map { it.url }.filter { "tern-apps.json" in it })
        }
    }

    private companion object {
        const val EXPORT = "https://files.test/tern-apps.json"
        const val PAGE = "https://files.test/page"
    }
}
