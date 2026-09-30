package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.GitHubProxy
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.xml.XmlException
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.io.IOException
import java.security.MessageDigest

class GitHubSource : Source {
    override val type = SourceTypes.GITHUB

    override fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        if (Urls.host(normalized).removePrefix("www.") != "github.com") return null
        val segments = Urls.segments(normalized)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (owner.lowercase() in RESERVED_OWNERS) return null
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null
        return SourceSpec(type, "https://github.com/$owner/$repo")
    }

    /** A repository on a GitHub of another host, such as GitHub Enterprise, when the person says the address is one. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        val at = Urls.authority(normalized)
        if (at.isEmpty() || at.removePrefix("www.") == "github.com") return match(normalized)
        val segments = Urls.segments(normalized)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null
        return SourceSpec(type, "https://$at/$owner/$repo")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val (owner, repo) = ownerRepo(spec)
        val token = context.tokens.tokenFor(Urls.host(apiBase(spec.url)))
        // The feed names releases only, so it cannot tell that the tags of a project without any changed.
        val fromTags = context.app?.trackOnly == true && context.validators.get(validatorKey(spec, "tags")) != null

        val pending = if (token == null && !fromTags) {
            when (val outcome = checkFeed(owner, repo, spec, context)) {
                FeedOutcome.Unchanged -> return CheckResult.Unchanged
                is FeedOutcome.NeedsApi -> outcome
            }
        } else {
            null
        }

        return checkApi(owner, repo, spec, context, token, pending)
    }

    /** A file behind the API is asked for as the file itself. The token for the API goes with it, and not past a redirect to another host. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download =
        if (isApiAssetUrl(asset.url)) Download(asset.url, mapOf("Accept" to "application/octet-stream")) else Download(asset.url)

    private fun ownerRepo(spec: SourceSpec): Pair<String, String> {
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) throw SourceException(SourceErrorKind.PARSE, "Bad GitHub spec ${spec.url}")
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private sealed interface FeedOutcome {
        data object Unchanged : FeedOutcome
        data class NeedsApi(val feedValidator: Validator, val fingerprint: String, val movedTo: String?) : FeedOutcome
    }

    private fun checkFeed(owner: String, repo: String, spec: SourceSpec, context: CheckContext): FeedOutcome {
        val home = Urls.authority(spec.url)
        val feedUrl = "https://$home/$owner/$repo/releases.atom"
        val feedKey = validatorKey(spec, "feed")
        val stored = context.validators.get(feedKey)

        val response = fetch(feedUrl, stored?.conditionalHeaders().orEmpty(), context)
        response.use {
            if (it.isNotModified) return FeedOutcome.Unchanged
            val moved = movedTo(it.url, owner, repo, home)
            if (!it.isSuccess) return FeedOutcome.NeedsApi(Validator.from(it.headers), "", moved)

            val fingerprint = try {
                feedFingerprint(it.text())
            } catch (e: XmlException) {
                return FeedOutcome.NeedsApi(Validator.from(it.headers), "", moved)
            }

            val previous = context.validators.get(validatorKey(spec, "feed-state"))?.etag
            if (previous == fingerprint) return FeedOutcome.Unchanged
            return FeedOutcome.NeedsApi(Validator.from(it.headers), fingerprint, moved)
        }
    }

    private fun checkApi(
        owner: String,
        repo: String,
        spec: SourceSpec,
        context: CheckContext,
        token: String?,
        pending: FeedOutcome.NeedsApi?,
    ): CheckResult {
        val home = Urls.authority(spec.url)
        val api = apiBase(spec.url)
        val url = "$api/repos/$owner/$repo/releases?per_page=30"
        val apiKey = validatorKey(spec, "api")
        val stored = context.validators.get(apiKey)

        var used = token
        var response = fetch(url, API_HEADERS + stored?.conditionalHeaders().orEmpty(), context, used?.let { "Bearer $it" })
        if (used != null && (response.status == 401 || response.status == 403)) {
            // A token that is refused, or that may not see this project, must not stop a public project from being
            // followed. The listing is asked for in full, so no file keeps an address that needs the token.
            response.close()
            used = null
            response = fetch(url, API_HEADERS, context)
        }
        response.use {
            if (it.isNotModified) {
                remember(spec, context, pending)
                return CheckResult.Unchanged
            }
            when (it.status) {
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Repository not found: $owner/$repo")
                401, 403 -> throw SourceException(
                    SourceErrorKind.AUTH,
                    if (token != null) "GitHub refused the request for $owner/$repo with the saved token and without it" else "GitHub refused the request for $owner/$repo",
                )
            }
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for $owner/$repo")

            val json = try {
                Json.parseArray(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub releases JSON for $owner/$repo", cause = e)
            }

            val assetDate = spec.flag(SourceOptions.ASSET_DATE)
            val listed = json.objects().asSequence()
                .filterNot { obj -> obj.bool("draft") == true }
                .mapNotNull { obj -> mapRelease(obj, assetDate, home, used != null) }
                .take(MAX_RELEASES)
                .toList()
            val releases = if (spec.flag(SourceOptions.VERIFY_LATEST)) withLatest(listed, latest(owner, repo, spec, context, used, assetDate), MAX_RELEASES) else listed
            val movedTo = pending?.movedTo ?: movedTo(it.url, owner, repo, home, apiPath = true)
            if (releases.isEmpty()) {
                if (context.app?.trackOnly == true) return tags(owner, repo, spec, context, used, pending, movedTo)
                throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $owner/$repo")
            }

            context.validators.put(apiKey, Validator.from(it.headers))
            context.validators.remove(validatorKey(spec, "tags"))
            remember(spec, context, pending)

            return listing(owner, repo, home, releases, movedTo)
        }
    }

    /**
     * The tags of a project that has no releases, for an app that is only tracked, as Obtainium
     * lists them: each one a release, with no file and no date.
     */
    private fun tags(owner: String, repo: String, spec: SourceSpec, context: CheckContext, token: String?, pending: FeedOutcome.NeedsApi?, movedTo: String?): CheckResult {
        val home = Urls.authority(spec.url)
        val key = validatorKey(spec, "tags")
        val headers = API_HEADERS + context.validators.get(key)?.conditionalHeaders().orEmpty()
        fetch("${apiBase(spec.url)}/repos/$owner/$repo/tags?per_page=30", headers, context, token?.let { "Bearer $it" }).use {
            if (it.isNotModified) {
                remember(spec, context, pending)
                return CheckResult.Unchanged
            }
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Repository not found: $owner/$repo")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for the tags of $owner/$repo")
            val json = try {
                Json.parseArray(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub tags JSON for $owner/$repo", cause = e)
            }
            val releases = json.objects().asSequence().mapNotNull { tag ->
                val name = tag.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Release(id = name, version = name, pageUrl = "https://$home/$owner/$repo/releases/tag/${Urls.encodeSegment(name)}")
            }.take(MAX_RELEASES).toList()
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases or tags for $owner/$repo")
            context.validators.put(key, Validator.from(it.headers))
            remember(spec, context, pending)
            return listing(owner, repo, home, releases, movedTo)
        }
    }

    private fun listing(owner: String, repo: String, home: String, releases: List<Release>, movedTo: String?): CheckResult {
        val listing = SourceListing(releases = releases, name = repo, author = owner, movedTo = movedTo)
        val icons = if (home == GITHUB) ForgeIcons.gitHub(owner, repo) else emptyList()
        return CheckResult.Listing(listing.withIcons("https://$home/$owner/$repo", icons))
    }

    /** The release GitHub marks as latest, or null when it marks none. Asked only when the listing changed. */
    private fun latest(owner: String, repo: String, spec: SourceSpec, context: CheckContext, token: String?, assetDate: Boolean): Release? {
        fetch("${apiBase(spec.url)}/repos/$owner/$repo/releases/latest", API_HEADERS, context, token?.let { "Bearer $it" }).use {
            if (it.status == 404) return null
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for the latest release of $owner/$repo")
            val obj = try {
                Json.parseObject(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub latest release JSON for $owner/$repo", cause = e)
            }
            return if (obj.bool("draft") == true) null else mapRelease(obj, assetDate, Urls.authority(spec.url), token != null)
        }
    }

    /** Only once the API has answered, so a failed API call is retried instead of being masked by the feed. */
    private fun remember(spec: SourceSpec, context: CheckContext, pending: FeedOutcome.NeedsApi?) {
        if (pending == null) return
        context.validators.put(validatorKey(spec, "feed"), pending.feedValidator)
        if (pending.fingerprint.isNotEmpty()) {
            context.validators.put(validatorKey(spec, "feed-state"), Validator(pending.fingerprint, null))
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, context: CheckContext, authorization: String? = null): HttpResponse =
        try {
            context.http.execute(HttpRequest(url, headers = headers, authorization = authorization))
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "Failed to fetch $url", cause = e)
        }

    private fun feedFingerprint(xml: String): String {
        val root = XmlScanner.parse(xml)
        val parts = root.children("entry").joinToString("\n") { entry ->
            "${entry.childText("id").orEmpty()}|${entry.childText("updated").orEmpty()}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Where the project at [home] says it lives now; an API answer names it after "repos", on any GitHub. */
    private fun movedTo(finalUrl: String, owner: String, repo: String, home: String, apiPath: Boolean = false): String? {
        val all = Urls.segments(finalUrl)
        val segments = if (apiPath) all.dropWhile { it != "repos" }.drop(1) else all
        if (segments.size < 2) return null
        val newOwner = segments[0]
        val newRepo = segments[1].removeSuffix(".git")
        if (newOwner.equals(owner, ignoreCase = true) && newRepo.equals(repo, ignoreCase = true)) return null
        if (!RepoNames.isValid(newOwner) || !RepoNames.isValid(newRepo)) return null
        return "https://$home/$newOwner/$newRepo"
    }

    /**
     * With [assetDate], the release is dated by its newest file, or by its own date when no file
     * says. [authenticated] says the listing was read with the token; the files of a project on
     * github.com are then fetched through the API, which also serves them for a private project.
     * Another GitHub's files come from its own host.
     */
    private fun mapRelease(obj: JsonObject, assetDate: Boolean, home: String, authenticated: Boolean): Release? {
        val tag = obj.string("tag_name") ?: return null
        val files = obj.array("assets")?.objects().orEmpty().mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val apiUrl = if (authenticated && home == GITHUB) asset.string("url")?.let(GitHubProxy::unwrap)?.takeIf(::isApiAssetUrl) else null
            val downloadUrl = apiUrl
                ?: asset.string("browser_download_url")?.let(GitHubProxy::unwrap)?.takeIf { isAllowedAssetUrl(it, home) }
                ?: return@mapNotNull null
            val digest = asset.string("digest")
            val sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            asset to Asset(name = name, url = downloadUrl, size = asset.long("size"), sha256 = sha256, needsAuth = apiUrl != null)
        }
        val published = obj.string("published_at")?.let(Iso8601::parseMs)
        return Release(
            id = tag,
            version = tag,
            title = obj.string("name"),
            notes = obj.string("body"),
            notesFormat = NotesFormat.MARKDOWN,
            publishedAtMs = if (assetDate) newestFileMs(files.map { it.first }) ?: published else published,
            prerelease = obj.bool("prerelease") ?: false,
            pageUrl = obj.string("html_url")?.let(GitHubProxy::unwrap),
            assets = files.map { it.second },
        )
    }

    /** A file of github.com comes from GitHub's own hosts; one of another GitHub from that host or a host under it. */
    private fun isAllowedAssetUrl(url: String, home: String): Boolean {
        if (!Urls.isHttps(url)) return false
        val normalized = Urls.normalize(url) ?: return false
        val host = Urls.host(normalized)
        if (home != GITHUB) {
            val homeHost = Urls.host("https://$home")
            return host == homeHost || host.endsWith(".$homeHost")
        }
        return host == "github.com" || host.endsWith(".githubusercontent.com") || host == "githubusercontent.com"
    }

    /** The API's address of a release's file: the only file address the token may be sent to. */
    private fun isApiAssetUrl(url: String): Boolean = API_ASSET.matches(url)

    companion object {
        private const val MAX_RELEASES = 30
        private const val GITHUB = "github.com"
        private val API_ASSET = Regex("^https://api\\.github\\.com/repos/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/releases/assets/[0-9]{1,20}$")
        private val API_HEADERS = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        private val RESERVED_OWNERS = setOf(
            "settings", "orgs", "marketplace", "topics", "sponsors", "features",
            "notifications", "issues", "pulls", "login", "join", "about", "pricing",
            "security", "explore", "dashboard", "apps", "codespaces", "new", "organizations",
        )

        /**
         * Where the API of the GitHub that holds the project at [url] answers, as each kind of
         * GitHub documents it: api.github.com for github.com, api. before the host for GitHub
         * Enterprise Cloud under ghe.com, and /api/v3 on the host for a GitHub Enterprise Server.
         * A token goes to the host of this address and to no other.
         */
        fun apiBase(url: String): String {
            val home = Urls.authority(url)
            return when {
                home == GITHUB -> "https://api.github.com"
                home.endsWith(".ghe.com") -> "https://api.$home"
                else -> "https://$home/api/v3"
            }
        }
    }
}
