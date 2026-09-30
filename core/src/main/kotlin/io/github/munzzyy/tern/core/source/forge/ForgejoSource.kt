package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
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

/** Covers Codeberg, Forgejo and Gitea instances behind the same release API. */
class ForgejoSource : Source {
    override val type = SourceTypes.FORGEJO

    override fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        if (Urls.host(normalized) != "codeberg.org") return null
        return ownerRepoSpec(normalized, "codeberg.org")
    }

    /** A repository on a Forgejo or Gitea of any host, read without asking first whether one answers there. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        val at = Urls.authority(normalized).takeIf { it.isNotEmpty() } ?: return null
        return ownerRepoSpec(normalized, at)
    }

    override fun probe(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        val host = Urls.host(normalized)
        val at = Urls.authority(normalized)
        if (host == "codeberg.org") return null
        val segments = Urls.segments(normalized)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null

        val versionUrl = "https://$at/api/v1/version"
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
            return SourceSpec(type, "https://$at/$owner/$repo")
        }
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val host = Urls.host(spec.url)
        val at = Urls.authority(spec.url)
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) throw SourceException(SourceErrorKind.PARSE, "Bad Forgejo spec ${spec.url}")
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")

        val url = "https://$at/api/v1/repos/$owner/$repo/releases?limit=20"
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

            val assetDate = spec.flag(SourceOptions.ASSET_DATE)
            val listed = json.objects().asSequence()
                .filterNot { obj -> obj.bool("draft") == true }
                .mapNotNull { obj -> mapRelease(obj, assetDate) }
                .take(MAX_RELEASES)
                .toList()
            val releases = if (spec.flag(SourceOptions.VERIFY_LATEST)) withLatest(listed, latest(at, owner, repo, context, token, assetDate), MAX_RELEASES) else listed
            if (releases.isEmpty()) {
                if (context.app?.trackOnly == true) return tags(spec, owner, repo, context, token)
                throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $owner/$repo")
            }

            context.validators.put(key, Validator.from(it.headers))
            context.validators.remove(validatorKey(spec, "tags"))
            val listing = SourceListing(releases = releases, name = repo, author = owner)
            return CheckResult.Listing(listing.withIcons(spec.url, ForgeIcons.forgejo(at, owner, repo, context, token?.let { t -> "token $t" })))
        }
    }

    /**
     * The tags of a project that has no releases, for an app that is only tracked, as Obtainium
     * lists them: each one a release, with no file, dated by its commit.
     */
    private fun tags(spec: SourceSpec, owner: String, repo: String, context: CheckContext, token: String?): CheckResult {
        val at = Urls.authority(spec.url)
        val url = "https://$at/api/v1/repos/$owner/$repo/tags?limit=20"
        val key = validatorKey(spec, "tags")
        val stored = context.validators.get(key)
        val response = try {
            context.http.execute(HttpRequest(url, headers = stored?.conditionalHeaders().orEmpty(), authorization = token?.let { "token $it" }))
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "Failed to fetch $url", cause = e)
        }
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Repository not found: $owner/$repo")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "$at returned ${it.status} for the tags of $owner/$repo")
            val json = try {
                Json.parseArray(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed Forgejo tags JSON for $owner/$repo", cause = e)
            }
            val releases = json.objects().asSequence().mapNotNull { tag ->
                val name = tag.string("name")?.takeIf { n -> n.isNotBlank() } ?: return@mapNotNull null
                Release(
                    id = name,
                    version = name,
                    publishedAtMs = tag.obj("commit")?.string("created")?.let(Iso8601::parseMs),
                    pageUrl = "https://$at/$owner/$repo/releases/tag/${Urls.encodeSegment(name)}",
                )
            }.take(MAX_RELEASES).toList()
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases or tags for $owner/$repo")
            context.validators.put(key, Validator.from(it.headers))
            val listing = SourceListing(releases = releases, name = repo, author = owner)
            return CheckResult.Listing(listing.withIcons(spec.url, ForgeIcons.forgejo(at, owner, repo, context, token?.let { t -> "token $t" })))
        }
    }

    /** The release the forge marks as latest, or null when it marks none. Asked only when the listing changed. */
    private fun latest(at: String, owner: String, repo: String, context: CheckContext, token: String?, assetDate: Boolean): Release? {
        val url = "https://$at/api/v1/repos/$owner/$repo/releases/latest"
        val response = try {
            context.http.execute(HttpRequest(url, authorization = token?.let { "token $it" }))
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "Failed to fetch $url", cause = e)
        }
        response.use {
            if (it.status == 404) return null
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "$at returned ${it.status} for the latest release of $owner/$repo")
            val obj = try {
                Json.parseObject(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed Forgejo latest release JSON for $owner/$repo", cause = e)
            }
            return if (obj.bool("draft") == true) null else mapRelease(obj, assetDate)
        }
    }

    private fun ownerRepoSpec(url: String, at: String): SourceSpec? {
        val segments = Urls.segments(url)
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (!RepoNames.isValid(owner) || !RepoNames.isValid(repo)) return null
        return SourceSpec(type, "https://$at/$owner/$repo")
    }

    /** With [assetDate], the release is dated by its newest file, or by its own date when no file says. */
    private fun mapRelease(obj: JsonObject, assetDate: Boolean): Release? {
        val tag = obj.string("tag_name") ?: return null
        val files = obj.array("assets")?.objects().orEmpty().mapNotNull { asset ->
            val name = asset.string("name") ?: return@mapNotNull null
            val downloadUrl = asset.string("browser_download_url") ?: return@mapNotNull null
            if (!Urls.isHttps(downloadUrl) || Urls.normalize(downloadUrl) == null) return@mapNotNull null
            asset to Asset(name = name, url = downloadUrl, size = asset.long("size"))
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

    private companion object {
        const val MAX_RELEASES = 20
    }
}
