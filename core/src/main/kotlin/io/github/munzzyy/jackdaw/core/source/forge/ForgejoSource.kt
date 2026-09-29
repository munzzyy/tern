package io.github.munzzyy.jackdaw.core.source.forge

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonObject
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.NotesFormat
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.RateLimitedException
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import java.io.IOException

/** Covers Codeberg, Forgejo and Gitea instances behind the same release API. */
class ForgejoSource : Source {
    override val type = SourceTypes.FORGEJO

    override fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        if (Urls.host(normalized) != "codeberg.org") return null
        return ownerRepoSpec(normalized, "codeberg.org")
    }

    override fun probe(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        val host = Urls.host(normalized)
        if (host == "codeberg.org") return null
        val segments = Urls.segments(normalized)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null

        val versionUrl = "https://$host/api/v1/version"
        val response = try {
            context.http.execute(HttpRequest(versionUrl))
        } catch (_: IOException) {
            return null
        }
        response.use {
            if (!it.isSuccess) return null
            val obj = try {
                Json.parse(it.text()) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            if (obj.string("version") == null) return null
            return SourceSpec(type, "https://$host/$owner/$repo")
        }
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val host = Urls.host(spec.url)
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) throw SourceException(SourceErrorKind.PARSE, "Bad Forgejo spec ${spec.url}")
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")

        val url = "https://$host/api/v1/repos/$owner/$repo/releases?limit=20"
        val key = validatorKey(spec, "releases")
        val stored = context.validators.get(key)
        val token = context.tokens.tokenFor(host)

        val response = try {
            context.http.execute(
                HttpRequest(
                    url,
                    headers = stored?.conditionalHeaders().orEmpty(),
                    authorization = token?.let { "token $it" },
                ),
            )
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "Failed to fetch $url", cause = e)
        }

        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            when (it.status) {
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Repository not found: $owner/$repo")
                401, 403 -> throw SourceException(SourceErrorKind.AUTH, "$host rejected the request for $owner/$repo")
            }
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "$host returned ${it.status} for $owner/$repo")

            val json = try {
                Json.parseArray(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed Forgejo releases JSON for $owner/$repo", cause = e)
            }

            val releases = json.objects().asSequence()
                .filterNot { obj -> obj.bool("draft") == true }
                .mapNotNull(::mapRelease)
                .take(MAX_RELEASES)
                .toList()
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $owner/$repo")

            context.validators.put(key, Validator.from(it.headers))
            return CheckResult.Listing(SourceListing(releases = releases, name = repo, author = owner))
        }
    }

    private fun ownerRepoSpec(url: String, host: String): SourceSpec? {
        val segments = Urls.segments(url)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null
        return SourceSpec(type, "https://$host/$owner/$repo")
    }

    private fun mapRelease(obj: JsonObject): Release? {
        val tag = obj.string("tag_name") ?: return null
        val assets = obj.array("assets")?.objects().orEmpty().mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val downloadUrl = asset.string("browser_download_url") ?: return@mapNotNull null
            if (!Urls.isHttps(downloadUrl) || Urls.normalize(downloadUrl) == null) return@mapNotNull null
            Asset(name = name, url = downloadUrl, size = asset.long("size"))
        }
        return Release(
            id = tag,
            version = tag,
            title = obj.string("name"),
            notes = obj.string("body"),
            notesFormat = NotesFormat.MARKDOWN,
            publishedAtMs = obj.string("published_at")?.let(Iso8601::parseMs),
            prerelease = obj.bool("prerelease") ?: false,
            pageUrl = obj.string("html_url"),
            assets = assets,
        )
    }

    private companion object {
        const val MAX_RELEASES = 20
    }
}
