package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
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

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val (owner, repo) = ownerRepo(spec)
        val token = context.tokens.tokenFor("api.github.com")

        val pending = if (token == null) {
            when (val outcome = checkFeed(owner, repo, spec, context)) {
                FeedOutcome.Unchanged -> return CheckResult.Unchanged
                is FeedOutcome.NeedsApi -> outcome
            }
        } else {
            null
        }

        return checkApi(owner, repo, spec, context, token, pending)
    }

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
        val feedUrl = "https://github.com/$owner/$repo/releases.atom"
        val feedKey = validatorKey(spec, "feed")
        val stored = context.validators.get(feedKey)

        val response = fetch(feedUrl, stored?.conditionalHeaders().orEmpty(), context)
        response.use {
            if (it.isNotModified) return FeedOutcome.Unchanged
            val moved = movedTo(it.url, owner, repo)
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
        val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=30"
        val apiKey = validatorKey(spec, "api")
        val stored = context.validators.get(apiKey)
        val headers = API_HEADERS + stored?.conditionalHeaders().orEmpty()

        val response = fetch(url, headers, context, token?.let { "Bearer $it" })
        response.use {
            if (it.isNotModified) {
                remember(spec, context, pending)
                return CheckResult.Unchanged
            }
            when (it.status) {
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Repository not found: $owner/$repo")
                401, 403 -> throw SourceException(
                    SourceErrorKind.AUTH,
                    if (token != null) "GitHub rejected the saved token" else "GitHub refused the request for $owner/$repo",
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
                .mapNotNull { obj -> mapRelease(obj, assetDate) }
                .take(MAX_RELEASES)
                .toList()
            val releases = if (spec.flag(SourceOptions.VERIFY_LATEST)) withLatest(listed, latest(owner, repo, context, token, assetDate), MAX_RELEASES) else listed
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $owner/$repo")

            context.validators.put(apiKey, Validator.from(it.headers))
            remember(spec, context, pending)

            val movedTo = pending?.movedTo ?: movedTo(it.url, owner, repo, apiPath = true)
            val listing = SourceListing(releases = releases, name = repo, author = owner, movedTo = movedTo)
            return CheckResult.Listing(listing.withIcons(spec.url, ForgeIcons.gitHub(owner, repo)))
        }
    }

    /** The release GitHub marks as latest, or null when it marks none. Asked only when the listing changed. */
    private fun latest(owner: String, repo: String, context: CheckContext, token: String?, assetDate: Boolean): Release? {
        fetch("https://api.github.com/repos/$owner/$repo/releases/latest", API_HEADERS, context, token?.let { "Bearer $it" }).use {
            if (it.status == 404) return null
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for the latest release of $owner/$repo")
            val obj = try {
                Json.parseObject(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub latest release JSON for $owner/$repo", cause = e)
            }
            return if (obj.bool("draft") == true) null else mapRelease(obj, assetDate)
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

    private fun movedTo(finalUrl: String, owner: String, repo: String, apiPath: Boolean = false): String? {
        val segments = Urls.segments(finalUrl)
        val offset = if (apiPath) 1 else 0
        if (segments.size < offset + 2) return null
        if (apiPath && segments[0] != "repos") return null
        val newOwner = segments[offset]
        val newRepo = segments[offset + 1].removeSuffix(".git")
        if (newOwner.equals(owner, ignoreCase = true) && newRepo.equals(repo, ignoreCase = true)) return null
        if (!RepoNames.isValid(newOwner) || !RepoNames.isValid(newRepo)) return null
        return "https://github.com/$newOwner/$newRepo"
    }

    /** With [assetDate], the release is dated by its newest file, or by its own date when no file says. */
    private fun mapRelease(obj: JsonObject, assetDate: Boolean): Release? {
        val tag = obj.string("tag_name") ?: return null
        val files = obj.array("assets")?.objects().orEmpty().mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val downloadUrl = asset.string("browser_download_url") ?: return@mapNotNull null
            if (!isAllowedAssetUrl(downloadUrl)) return@mapNotNull null
            val digest = asset.string("digest")
            val sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            asset to Asset(name = name, url = downloadUrl, size = asset.long("size"), sha256 = sha256)
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
            pageUrl = obj.string("html_url"),
            assets = files.map { it.second },
        )
    }

    private fun isAllowedAssetUrl(url: String): Boolean {
        if (!Urls.isHttps(url)) return false
        val normalized = Urls.normalize(url) ?: return false
        val host = Urls.host(normalized)
        return host == "github.com" || host.endsWith(".githubusercontent.com") || host == "githubusercontent.com"
    }

    private companion object {
        const val MAX_RELEASES = 30
        val API_HEADERS = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        val RESERVED_OWNERS = setOf(
            "settings", "orgs", "marketplace", "topics", "sponsors", "features",
            "notifications", "issues", "pulls", "login", "join", "about", "pricing",
            "security", "explore", "dashboard", "apps", "codespaces", "new", "organizations",
        )
    }
}
