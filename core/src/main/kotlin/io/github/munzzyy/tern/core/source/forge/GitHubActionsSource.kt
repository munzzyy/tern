package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
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
import java.io.IOException

/** Follows the newest successful run of one workflow. The UI creates this spec; it is never matched from a URL. */
class GitHubActionsSource : Source {
    override val type = SourceTypes.GITHUB_ACTIONS

    override fun match(url: String): SourceSpec? = null

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val (owner, repo) = ownerRepo(spec)
        val workflow = spec.option(SourceOptions.WORKFLOW)
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "A workflow file name is required for a GitHub Actions source")
        val branch = spec.option(SourceOptions.BRANCH)
        val token = context.tokens.tokenFor(API_HOST)
            ?: throw SourceException(SourceErrorKind.AUTH, "A token is required to fetch CI artifacts from GitHub Actions")

        val runsUrl = buildString {
            append("https://api.github.com/repos/$owner/$repo/actions/workflows/")
            append(Urls.encodeSegment(workflow))
            append("/runs?status=success&per_page=5")
            if (branch != null) append("&branch=").append(Urls.encodeSegment(branch))
        }
        val runsKey = validatorKey(spec, "runs")
        val stored = context.validators.get(runsKey)
        val runsResponse = fetch(runsUrl, stored?.conditionalHeaders().orEmpty(), context, token)
        val run = runsResponse.use {
            if (it.isNotModified) return CheckResult.Unchanged
            when (it.status) {
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Workflow not found: $owner/$repo/$workflow")
                401, 403 -> throw SourceException(SourceErrorKind.AUTH, "GitHub rejected the token for $owner/$repo")
            }
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for $owner/$repo")

            val json = try {
                Json.parseObject(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub Actions runs JSON for $owner/$repo", cause = e)
            }
            val runObj = json.array("workflow_runs")?.objects()?.firstOrNull()
                ?: throw SourceException(SourceErrorKind.NO_RELEASES, "No successful runs for $owner/$repo/$workflow")
            context.validators.put(runsKey, Validator.from(it.headers))
            runObj
        }

        val runId = run.long("id")
            ?: throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub Actions run for $owner/$repo")
        val runNumber = run.long("run_number") ?: 0L
        val headSha = run.string("head_sha").orEmpty()
        val artifactsUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts"
        val artifactsResponse = fetch(artifactsUrl, emptyMap(), context, token)
        val assets = artifactsResponse.use {
            when (it.status) {
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Run not found: $owner/$repo/$runId")
                401, 403 -> throw SourceException(SourceErrorKind.AUTH, "GitHub rejected the token for $owner/$repo")
            }
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${it.status} for $owner/$repo")
            val json = try {
                Json.parseObject(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitHub Actions artifacts JSON for $owner/$repo", cause = e)
            }
            json.array("artifacts")?.objects().orEmpty()
                .filterNot { artifact -> artifact.bool("expired") == true }
                .mapNotNull(::mapArtifact)
        }
        if (assets.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No usable artifacts for $owner/$repo/$runId")

        val version = "$runNumber-${headSha.take(7)}"
        val release = Release(
            id = runId.toString(),
            version = version,
            publishedAtMs = run.string("updated_at")?.let(Iso8601::parseMs),
            prerelease = true,
            pageUrl = run.string("html_url"),
            assets = assets,
        )
        val listing = SourceListing(releases = listOf(release), name = repo, author = owner)
        return CheckResult.Listing(listing.withIcons(spec.url, ForgeIcons.gitHub(owner, repo)))
    }

    private fun mapArtifact(obj: JsonObject): Asset? {
        val name = obj.string("name") ?: return null
        val named = obj.string("archive_download_url") ?: return null
        if (!Urls.isHttps(named)) return null
        val url = Urls.normalize(named)?.takeIf { Urls.authority(it) == API_HOST } ?: return null
        return Asset(
            name = "$name.zip",
            url = url,
            size = obj.long("size_in_bytes"),
            kind = AssetKind.ARCHIVE,
            needsAuth = true,
            holdsApps = true,
        )
    }

    private fun ownerRepo(spec: SourceSpec): Pair<String, String> {
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) throw SourceException(SourceErrorKind.PARSE, "Bad GitHub Actions spec ${spec.url}")
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private fun fetch(url: String, headers: Map<String, String>, context: CheckContext, token: String): HttpResponse =
        try {
            context.http.execute(
                HttpRequest(
                    url,
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "X-GitHub-Api-Version" to "2022-11-28",
                    ) + headers,
                    authorization = "Bearer $token",
                ),
            )
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "Failed to fetch $url", cause = e)
        }

    private companion object {
        /** The only host that is sent the token, so the only one a file may be fetched from. */
        const val API_HOST = "api.github.com"
    }
}
