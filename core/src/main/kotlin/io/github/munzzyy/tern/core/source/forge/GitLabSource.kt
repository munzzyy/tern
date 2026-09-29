package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
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
import io.github.munzzyy.tern.core.source.SourceTypes
import java.io.IOException

class GitLabSource : Source {
    override val type = SourceTypes.GITLAB

    override fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        if (Urls.host(normalized) != "gitlab.com") return null
        return projectSpec(normalized, "gitlab.com")
    }

    override fun probe(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        val host = Urls.host(normalized)
        val at = Urls.authority(normalized)
        if (host == "gitlab.com") return null
        val segments = projectSegments(normalized) ?: return null
        val projectPath = segments.joinToString("/")

        val apiUrl = "https://$at/api/v4/projects/${Urls.encodeSegment(projectPath)}"
        val response = try {
            context.http.execute(HttpRequest(apiUrl))
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
            val pathWithNamespace = obj.string("path_with_namespace") ?: return null
            return SourceSpec(type, "https://$at/$pathWithNamespace")
        }
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val host = Urls.host(spec.url)
        val at = Urls.authority(spec.url)
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) throw SourceException(SourceErrorKind.PARSE, "Bad GitLab spec ${spec.url}")
        val projectPath = segments.joinToString("/")

        val url = "https://$at/api/v4/projects/${Urls.encodeSegment(projectPath)}/releases?per_page=20"
        val key = validatorKey(spec, "releases")
        val stored = context.validators.get(key)
        val token = context.tokens.tokenFor(host)

        val response = try {
            context.http.execute(
                HttpRequest(
                    url,
                    headers = stored?.conditionalHeaders().orEmpty(),
                    authorization = token?.let { "Bearer $it" },
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
                404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "Project not found: $projectPath")
                401, 403 -> throw SourceException(SourceErrorKind.AUTH, "GitLab rejected the request for $projectPath")
            }
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "GitLab returned ${it.status} for $projectPath")

            val json = try {
                Json.parseArray(it.text())
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Malformed GitLab releases JSON for $projectPath", cause = e)
            }

            val releases = json.objects().asSequence()
                .filterNot { obj -> obj.bool("upcoming_release") == true }
                .mapNotNull { obj -> mapRelease(obj, spec.url) }
                .take(MAX_RELEASES)
                .toList()
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $projectPath")

            context.validators.put(key, Validator.from(it.headers))
            val listing = SourceListing(releases = releases, name = segments.last(), author = segments.dropLast(1).joinToString("/"))
            return CheckResult.Listing(listing.withIcons(spec.url, ForgeIcons.gitLab(at, projectPath, context, token?.let { t -> "Bearer $t" })))
        }
    }

    private fun projectSegments(url: String): List<String>? {
        val raw = Urls.segments(url)
        val cut = raw.indexOf("-")
        val projectSegments = if (cut >= 0) raw.subList(0, cut) else raw
        if (projectSegments.size < 2) return null
        val cleaned = projectSegments.mapIndexed { index, segment ->
            if (index == projectSegments.lastIndex) segment.removeSuffix(".git") else segment
        }
        if (cleaned.any { !RepoNames.isValid(it) }) return null
        return cleaned
    }

    private fun projectSpec(url: String, at: String): SourceSpec? {
        val segments = projectSegments(url) ?: return null
        return SourceSpec(type, "https://$at/${segments.joinToString("/")}")
    }

    private fun mapRelease(obj: JsonObject, projectUrl: String): Release? {
        val tag = obj.string("tag_name") ?: return null
        val linkAssets = obj.obj("assets")?.array("links")?.objects().orEmpty().mapNotNull { link ->
            val label = link.string("name") ?: return@mapNotNull null
            val assetUrl = link.string("direct_asset_url") ?: link.string("url") ?: return@mapNotNull null
            if (!Urls.isHttps(assetUrl) || Urls.normalize(assetUrl) == null) return@mapNotNull null
            Asset(name = fileName(label, assetUrl), url = assetUrl)
        }
        val description = obj.string("description")
        val uploadAssets = description?.let { extractUploadLinks(it, projectUrl) }.orEmpty()

        return Release(
            id = tag,
            version = tag,
            title = obj.string("name"),
            notes = description,
            notesFormat = NotesFormat.MARKDOWN,
            publishedAtMs = (obj.string("released_at") ?: obj.string("created_at"))?.let(Iso8601::parseMs),
            prerelease = false,
            pageUrl = obj.obj("_links")?.string("self")?.takeIf(Urls::isHttps) ?: "$projectUrl/-/releases/${Urls.encodeSegment(tag)}",
            assets = linkAssets + uploadAssets,
        )
    }

    /** Link names are free text; when one hides what the file is, the URL's own file name says it. */
    private fun fileName(label: String, url: String): String {
        if (Asset.kindOf(label) != AssetKind.OTHER) return label
        val fromUrl = Urls.segments(url).lastOrNull() ?: return label
        return if (Asset.kindOf(fromUrl) != AssetKind.OTHER) fromUrl else label
    }

    private fun extractUploadLinks(markdown: String, projectUrl: String): List<Asset> =
        UPLOAD_LINK.findAll(markdown.take(MAX_MARKDOWN_LENGTH)).map { match ->
            val hex = match.groupValues[1]
            val file = match.groupValues[2]
            Asset(name = file, url = "$projectUrl/uploads/$hex/$file")
        }.toList()

    private companion object {
        const val MAX_RELEASES = 20
        const val MAX_MARKDOWN_LENGTH = 20_000
        val UPLOAD_LINK = Regex("""\[[^\]]*]\(/uploads/([0-9a-fA-F]{32})/([^)\s]+)\)""")
    }
}
