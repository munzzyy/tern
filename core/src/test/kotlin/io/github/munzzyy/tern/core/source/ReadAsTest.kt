package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An address read as the kind of source the person says it is, as Obtainium's "override source" reads it. */
class ReadAsTest {
    private val registry = SourceRegistry.standard()

    private fun context(http: FakeHttp = FakeHttp(), tokens: TokenProvider = TokenProvider.NONE) = CheckContext(http, InMemoryValidatorStore(), tokens)

    @Test
    fun aForgeOnAHostOfItsOwnIsReadWithoutAskingIt() {
        val quiet = FakeHttp()
        assertEquals(SourceSpec(SourceTypes.GITLAB, "https://git.example.org/group/sub/app"), registry.readAs("git.example.org/group/sub/app/-/releases", SourceTypes.GITLAB, context(quiet)))
        assertEquals(SourceSpec(SourceTypes.FORGEJO, "https://forge.example.org:3000/owner/app"), registry.readAs("https://forge.example.org:3000/owner/app.git", SourceTypes.FORGEJO, context(quiet)))
        assertEquals(SourceSpec(SourceTypes.GITHUB, "https://github.example.com/owner/app"), registry.readAs("https://github.example.com/owner/app/releases", SourceTypes.GITHUB, context(quiet)))
        assertEquals(SourceSpec(SourceTypes.SOURCEHUT, "https://git.example.org/~someone/app"), registry.readAs("https://git.example.org/~someone/app", SourceTypes.SOURCEHUT, context(quiet)))
        assertTrue(quiet.requests.isEmpty())
    }

    @Test
    fun anyPageOrFileAddressIsReadAsWhatThePersonSays() {
        val address = "https://example.org/get?app=1"
        assertEquals(SourceSpec(SourceTypes.HTML, address), registry.readAs(address, SourceTypes.HTML, context()))
        assertEquals(SourceSpec(SourceTypes.DIRECT, address), registry.readAs(address, SourceTypes.DIRECT, context()))
        assertEquals(SourceTypes.GITHUB_ACTIONS, registry.readAs("https://github.com/owner/app", SourceTypes.GITHUB_ACTIONS, context())?.type)
    }

    @Test
    fun whatCannotBeReadThatWayIsNot() {
        assertNull(registry.readAs("https://example.org", SourceTypes.GITHUB, context()))
        assertNull(registry.readAs("https://github.com/settings/profile", SourceTypes.GITHUB, context()))
        assertNull(registry.readAs("https://example.org/owner/app", SourceTypes.VIVO, context()))
        assertNull(registry.readAs("https://example.org/owner/app", "coolapk", context()))
        assertNull(registry.readAs("https://example.org/owner/app", "nonsense", context()))
    }

    @Test
    fun aRepositoryGivenByItsSitesAddressIsFoundWhereItsIndexIs() {
        val http = FakeHttp()
            .on("https://apps.example.org/entry.jar") { HttpResponse.of(404, "", url = it.url) }
            .on("https://apps.example.org/index-v1.jar") { HttpResponse.of(404, "", url = it.url) }
            .on("https://apps.example.org/repo/entry.jar") { HttpResponse.of(404, "", url = it.url) }
            .on("https://apps.example.org/repo/index-v1.jar") { HttpResponse.of(404, "", url = it.url) }
            .on("https://apps.example.org/fdroid/repo/entry.jar") { HttpResponse.of(200, "", Headers.EMPTY, it.url) }
        val spec = registry.readAs("https://apps.example.org/", SourceTypes.FDROID_REPO, context(http))
        assertEquals(SourceSpec(SourceTypes.FDROID_REPO, "https://apps.example.org/fdroid/repo"), spec)
        assertTrue(http.requests.all { it.method == "HEAD" })
    }

    @Test
    fun anAppOfARepositoryAtAnAddressOfItsOwnShapeIsTakenAsItIs() {
        val quiet = FakeHttp()
        val spec = registry.readAs("https://apps.example.org/mine?package=org.example.app", SourceTypes.FDROID_REPO, context(quiet))
        assertEquals("https://apps.example.org/mine", spec?.url)
        assertEquals("org.example.app", spec?.option(SourceOptions.PACKAGE))
        assertTrue(quiet.requests.isEmpty())
    }

    @Test
    fun aRepositoryIsSearchedByEveryWordInPackageNameOrSummary() {
        val repoUrl = "https://example.com/fdroid/repo"
        val http = FakeHttp()
            .bytes("$repoUrl/entry.jar", Fixtures.bytes("fdroid/repo/entry-many.jar"))
            .bytes("$repoUrl/index-v2-many.json", Fixtures.bytes("fdroid/repo/index-v2-many.json"))
        val source = FDroidRepoSource()
        // App 054 is there by its package, org.example.many.n150.
        val found = source.listApps(SourceSpec(SourceTypes.FDROID_REPO, repoUrl), context(http), words = "APP  150")
        assertEquals(listOf("App 054", "App 150"), found.apps.map { it.name })
        assertTrue(!found.more)
        val none = source.listApps(SourceSpec(SourceTypes.FDROID_REPO, repoUrl), context(http), words = "nothing like it")
        assertTrue(none.apps.isEmpty())
    }

    @Test
    fun aGitHubOfAnotherHostIsAskedWhereItsApiIsAndOnlyItsOwnTokenGoesThere() {
        assertEquals("https://api.github.com", GitHubSource.apiBase("https://github.com/owner/app"))
        assertEquals("https://api.octo.ghe.com", GitHubSource.apiBase("https://octo.ghe.com/owner/app"))
        assertEquals("https://git.example.org/api/v3", GitHubSource.apiBase("https://git.example.org/owner/app"))

        val releases = "https://git.example.org/api/v3/repos/owner/app/releases?per_page=30"
        val body = """[{"tag_name":"v1.0","name":"One","assets":[
            {"name":"app.apk","browser_download_url":"https://git.example.org/owner/app/releases/download/v1.0/app.apk","size":10},
            {"name":"elsewhere.apk","browser_download_url":"https://files.example.net/app.apk","size":10}]}]"""
        val http = FakeHttp().text(releases, body)
        val tokens = TokenProvider { host -> if (host == "git.example.org") "secret" else "wrong" }
        val result = GitHubSource().check(SourceSpec(SourceTypes.GITHUB, "https://git.example.org/owner/app"), context(http, tokens)) as CheckResult.Listing
        assertEquals("Bearer secret", http.requests.single().authorization)
        assertEquals(listOf("app.apk"), result.listing.releases.single().assets.map { it.name })
    }
}
