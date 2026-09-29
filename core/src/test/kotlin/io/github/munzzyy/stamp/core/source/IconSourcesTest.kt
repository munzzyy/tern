package io.github.munzzyy.stamp.core.source

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.RateLimitedException
import io.github.munzzyy.stamp.core.net.Validator
import io.github.munzzyy.stamp.core.source.fdroid.FDroidIcons
import io.github.munzzyy.stamp.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.stamp.core.source.fdroid.FDroidSource
import io.github.munzzyy.stamp.core.source.forge.ForgejoSource
import io.github.munzzyy.stamp.core.source.forge.GitHubActionsSource
import io.github.munzzyy.stamp.core.source.forge.GitHubSource
import io.github.munzzyy.stamp.core.source.forge.GitLabSource
import io.github.munzzyy.stamp.core.testing.FakeHttp
import io.github.munzzyy.stamp.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** For each source that offers an icon: the answer of the service in, the addresses to try out. */
class IconSourcesTest {
    private val storeIcon = "fastlane/metadata/android/en-US/images/icon.png"

    private fun icons(result: CheckResult): List<String> = (result as CheckResult.Listing).listing.iconUrls

    private fun context(http: FakeHttp, tokenFor: String? = null) = CheckContext(
        http = http,
        validators = InMemoryValidatorStore(),
        tokens = TokenProvider { host -> "tok".takeIf { host == tokenFor } },
    )

    @Test
    fun gitHubOffersTheStoreListingThenTheOwnersAvatar() {
        val http = FakeHttp()
            .resource("https://github.com/example/app/releases.atom", "forge/github_feed.atom")
            .resource("https://api.github.com/repos/example/app/releases?per_page=30", "forge/github_releases.json")
        val listing = (GitHubSource().check(SourceSpec(SourceTypes.GITHUB, "https://github.com/example/app"), context(http)) as CheckResult.Listing).listing

        assertEquals("https://raw.githubusercontent.com/example/app/HEAD/$storeIcon", listing.iconUrl)
        assertEquals(listOf("https://avatars.githubusercontent.com/example?s=192"), listing.iconFallbacks)
        assertEquals("asking for the addresses costs no request", 2, http.requests.size)
    }

    @Test
    fun gitHubActionsOffersWhatGitHubOffers() {
        val spec = SourceSpec(SourceTypes.GITHUB_ACTIONS, "https://github.com/example/app", mapOf(SourceOptions.WORKFLOW to "build.yml"))
        val http = FakeHttp()
            .resource("https://api.github.com/repos/example/app/actions/workflows/build.yml/runs?status=success&per_page=5", "forge/github_workflow_runs.json")
            .resource("https://api.github.com/repos/example/app/actions/runs/555/artifacts", "forge/github_artifacts.json")

        assertEquals(
            listOf("https://raw.githubusercontent.com/example/app/HEAD/$storeIcon", "https://avatars.githubusercontent.com/example?s=192"),
            icons(GitHubActionsSource().check(spec, context(http, tokenFor = "api.github.com"))),
        )
    }

    private val gitLab = SourceSpec(SourceTypes.GITLAB, "https://gitlab.com/group/app")
    private val gitLabReleases = "https://gitlab.com/api/v4/projects/group%2Fapp/releases?per_page=20"
    private val gitLabProject = "https://gitlab.com/api/v4/projects/group%2Fapp"
    private val gitLabStoreIcon = "https://gitlab.com/group/app/-/raw/HEAD/$storeIcon"

    private fun gitLabWith(project: FakeHttp.() -> FakeHttp) = FakeHttp().resource(gitLabReleases, "forge/gitlab_releases.json").project()

    @Test
    fun gitLabOffersTheProjectAvatarThenTheStoreListingThenTheGroupAvatar() {
        val http = gitLabWith { resource(gitLabProject, "icons/gitlab_project.json") }
        assertEquals(
            listOf(
                "https://gitlab.com/uploads/-/system/project/avatar/1/launcher.png?v=1700000000",
                gitLabStoreIcon,
                "https://gitlab.com/uploads/-/system/group/avatar/2/group.png?v=1700000000",
            ),
            icons(GitLabSource().check(gitLab, context(http))),
        )
    }

    @Test
    fun aGitLabProjectWithoutAvatarsOffersTheStoreListingAlone() {
        val http = gitLabWith { resource(gitLabProject, "icons/gitlab_project_bare.json") }
        assertEquals(listOf(gitLabStoreIcon), icons(GitLabSource().check(gitLab, context(http))))
    }

    @Test
    fun aGitLabAvatarOnAnotherHostOrOverPlainHttpIsDropped() {
        val http = gitLabWith { resource(gitLabProject, "icons/gitlab_project_elsewhere.json") }
        assertEquals(listOf(gitLabStoreIcon), icons(GitLabSource().check(gitLab, context(http))))
    }

    @Test
    fun aGitLabProjectThatCannotBeAskedStillGivesItsReleases() {
        val answers = listOf<FakeHttp.() -> FakeHttp>(
            { this },
            { text(gitLabProject, "not here", status = 404) },
            { text(gitLabProject, "oops", status = 500) },
            { text(gitLabProject, Fixtures.text("icons/gitlab_project.json"), status = 500) },
            { text(gitLabProject, "<html>") },
            { text(gitLabProject, "[1, 2]") },
            { text(gitLabProject, "{\"avatar_url\": 7, \"namespace\": \"group\"}") },
            { on(gitLabProject) { throw RateLimitedException("gitlab.com", 5L) } },
            { on(gitLabProject) { throw IllegalStateException("broken transport") } },
        )
        for (answer in answers) {
            val listing = (GitLabSource().check(gitLab, context(gitLabWith(answer))) as CheckResult.Listing).listing
            assertEquals(1, listing.releases.size)
            assertEquals(listOf(gitLabStoreIcon), listing.iconUrls)
        }
    }

    @Test
    fun theGitLabProjectIsAskedWithTheTokenOfItsOwnHost() {
        val http = gitLabWith { resource(gitLabProject, "icons/gitlab_project.json") }
        GitLabSource().check(gitLab, context(http, tokenFor = "gitlab.com"))
        assertEquals("Bearer tok", http.requestsTo(gitLabProject).single().authorization)
    }

    private val forgejo = SourceSpec(SourceTypes.FORGEJO, "https://codeberg.org/example/app")
    private val forgejoReleases = "https://codeberg.org/api/v1/repos/example/app/releases?limit=20"
    private val forgejoRepository = "https://codeberg.org/api/v1/repos/example/app"

    private fun forgejoWith(repository: FakeHttp.() -> FakeHttp) = FakeHttp().resource(forgejoReleases, "forge/forgejo_releases.json").repository()

    @Test
    fun forgejoOffersTheRepositoryAvatarThenTheOwners() {
        val http = forgejoWith { resource(forgejoRepository, "icons/forgejo_repository.json") }
        assertEquals(
            listOf(
                "https://codeberg.org/repo-avatars/0f1e2d3c4b5a69788796a5b4c3d2e1f0",
                "https://codeberg.org/avatars/00112233445566778899aabbccddeeff",
            ),
            icons(ForgejoSource().check(forgejo, context(http))),
        )
    }

    @Test
    fun aForgejoRepositoryWithoutAnAvatarOffersItsOwners() {
        val http = forgejoWith { resource(forgejoRepository, "icons/forgejo_repository_bare.json") }
        assertEquals(listOf("https://codeberg.org/avatars/00112233445566778899aabbccddeeff"), icons(ForgejoSource().check(forgejo, context(http))))
    }

    @Test
    fun aForgejoRepositoryThatCannotBeAskedStillGivesItsReleases() {
        val answers = listOf<FakeHttp.() -> FakeHttp>(
            { this },
            { text(forgejoRepository, "gone", status = 404) },
            { text(forgejoRepository, Fixtures.text("icons/forgejo_repository.json"), status = 403) },
            { text(forgejoRepository, "{") },
        )
        for (answer in answers) {
            val listing = (ForgejoSource().check(forgejo, context(forgejoWith(answer))) as CheckResult.Listing).listing
            assertTrue(listing.releases.isNotEmpty())
            assertEquals(emptyList<String>(), listing.iconUrls)
        }
    }

    @Test
    fun fDroidAndIzzyOnDroidOfferTheIconNextToTheirFiles() {
        val source = FDroidSource()
        val fdroid = FakeHttp().resource("https://f-droid.org/api/v1/packages/org.example.app", "icons/fdroid_package.json")
        assertEquals(
            listOf("https://f-droid.org/repo/org.example.app/en-US/icon.png"),
            icons(source.check(source.match("https://f-droid.org/packages/org.example.app")!!, context(fdroid))),
        )

        val izzy = FakeHttp().resource("https://apt.izzysoft.de/fdroid/api/v1/packages/org.example.app", "icons/fdroid_package.json")
        assertEquals(
            listOf("https://apt.izzysoft.de/fdroid/repo/org.example.app/en-US/icon.png"),
            icons(source.check(source.match("https://apt.izzysoft.de/fdroid/index/apk/org.example.app")!!, context(izzy))),
        )
    }

    @Test
    fun anAnswerThatNamesNoPackageNameIsRefusedBeforeAnyAddressIsBuilt() {
        val source = FDroidSource()
        val body = "{\"packageName\": \"../../other\", \"suggestedVersionCode\": 12, \"packages\": [{\"versionName\": \"1.2\", \"versionCode\": 12}]}"
        val http = FakeHttp().text("https://f-droid.org/api/v1/packages/org.example.app", body)
        try {
            source.check(source.match("https://f-droid.org/packages/org.example.app")!!, context(http))
            fail("took an answer that names ../../other")
        } catch (e: SourceException) {
            assertEquals(SourceErrorKind.PARSE, e.kind)
        }
    }

    private val repository = "https://example.com/fdroid/repo"

    private fun packageOf(text: String): JsonObject = Json.parseObject(text)

    @Test
    fun anIndexNamesTheIconByLocale() {
        val app = packageOf(Fixtures.text("icons/index_v2_package.json"))
        assertEquals("$repository/org.example.app/en-US/icon_ZW5faWNvbl9maXh0dXJl-_8=.png", FDroidIcons.fromIndex(app, repository))

        val german = packageOf("{\"metadata\": {\"icon\": {\"de\": {\"name\": \"/org.example.app/de/icon.png\"}}}}")
        assertEquals("$repository/org.example.app/de/icon.png", FDroidIcons.fromIndex(german, repository))

        assertNull(FDroidIcons.fromIndex(packageOf("{\"metadata\": {\"name\": {\"en-US\": \"Example\"}}}"), repository))
        assertNull(FDroidIcons.fromIndex(packageOf("{\"metadata\": {\"icon\": \"icon.png\"}}"), repository))
        assertNull(FDroidIcons.fromIndex(packageOf("{\"metadata\": {\"icon\": {\"en-US\": {\"name\": 7}}}}"), repository))
    }

    @Test
    fun anIconNameThatLeavesTheRepositoryIsDropped() {
        for (name in listOf("/../other/icon.png", "//other.example.net/icon.png", "icon.png", "/a/./icon.png", "/icon.png?x=1", "/icon.png#x", "/a\\b.png", "/a\nb.png")) {
            val app = Json.obj("metadata" to Json.obj("icon" to Json.obj("en-US" to Json.obj("name" to name))))
            assertNull(name, FDroidIcons.fromIndex(app, repository))
        }
    }

    @Test
    fun anIndexInTheFirstFormatNamesTheIconOfTheApp() {
        val root = Json.parseObject(Fixtures.text("icons/index_v1.json"))
        assertEquals("$repository/org.example.app/en-US/icon_ZW5faWNvbl9maXh0dXJl-_8=.png", FDroidIcons.fromFirstIndex(root, "org.example.app", repository))
        assertEquals("$repository/org.example.german/de/icon_ZGU=.png", FDroidIcons.fromFirstIndex(root, "org.example.german", repository))
        assertNull(FDroidIcons.fromFirstIndex(root, "org.example.plain", repository))
        assertNull(FDroidIcons.fromFirstIndex(root, "org.example.escaping", repository))
        assertNull(FDroidIcons.fromFirstIndex(root, "org.example.absent", repository))
    }

    @Test
    fun aRepositoryHandsTheIconOfItsIndexToTheListing() {
        val source = FDroidRepoSource()
        val spec = SourceSpec(SourceTypes.FDROID_REPO, repository, mapOf(SourceOptions.PACKAGE to "org.example.two"))
        val validators = InMemoryValidatorStore()
        val first = FakeHttp()
            .bytes("$repository/entry.jar", Fixtures.bytes("fdroid/repo/entry.jar"))
            .bytes("$repository/index-v2.json", Fixtures.bytes("fdroid/repo/index-v2.json"))
        assertEquals(emptyList<String>(), icons(source.check(spec, CheckContext(first, validators))))

        val key = source.validatorKey(spec, "package:org.example.two")
        val held = validators.get(key)!!
        val app = Json.parseObject(held.etag!!)
        val icon = Json.obj("en-US" to Json.obj("name" to "/org.example.two/en-US/icon_dHdv.png", "sha256" to "3".repeat(64), "size" to 1500))
        val metadata = JsonObject(app.obj("metadata")!!.fields + ("icon" to icon))
        validators.put(key, Validator(Json.write(JsonObject(app.fields + ("metadata" to metadata))), held.lastModified))

        val next = FakeHttp()
            .bytes("$repository/entry.jar", Fixtures.bytes("fdroid/repo/entry-next.jar"))
            .bytes("$repository/diff/1700000000000.json", Fixtures.bytes("fdroid/repo/diff/1700000000000.json"))
        val listing = (source.check(spec, CheckContext(next, validators)) as CheckResult.Listing).listing
        assertEquals(listOf("0.6"), listing.releases.map { it.version })
        assertEquals(listOf("$repository/org.example.two/en-US/icon_dHdv.png"), listing.iconUrls)
    }

    @Test
    fun aListingTakesOnlyTheAddressesItsSourceMayName() {
        val listing = SourceListing(emptyList()).withIcons(
            "https://codeberg.org/example/app",
            listOf(null, "http://codeberg.org/plain.png", "https://elsewhere.example.net/a.png", "https://codeberg.org/a.png", "https://codeberg.org/b.png"),
        )
        assertEquals("https://codeberg.org/a.png", listing.iconUrl)
        assertEquals(listOf("https://codeberg.org/b.png"), listing.iconFallbacks)
        assertEquals(listOf("https://codeberg.org/a.png", "https://codeberg.org/b.png"), listing.iconUrls)
        assertNull(SourceListing(emptyList()).withIcons("https://codeberg.org/example/app", emptyList()).iconUrl)
    }
}
