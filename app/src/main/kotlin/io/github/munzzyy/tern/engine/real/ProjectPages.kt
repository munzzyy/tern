package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.engine.NoteBlock
import java.util.Base64

/**
 * The page a project keeps about itself, its README, read from the forge and shown as formatted
 * text. Where Obtainium can show the source's web page in a web view, Tern shows this: no script
 * runs and nothing but the forge's API is asked.
 */
internal class ProjectPages(private val http: HttpClient, private val tokens: TokenProvider) {
    /** The README of the project behind [spec], as blocks to show; null when its source keeps none Tern can read. */
    fun read(spec: SourceSpec): List<NoteBlock>? {
        val text = when (spec.type) {
            SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS -> github(spec)
            SourceTypes.GITLAB -> gitlab(spec)
            SourceTypes.FORGEJO -> forgejo(spec)
            else -> return null
        } ?: return null
        return NotesMapper.markdown(withoutHtml(text)).takeIf { it.isNotEmpty() }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap(), authorization: String? = null): String? =
        http.execute(HttpRequest(url, headers = headers, authorization = authorization)).use { if (it.isSuccess) it.text(MAX_BYTES) else null }

    private fun github(spec: SourceSpec): String? {
        val (owner, repo) = ownerRepo(spec) ?: return null
        val token = tokens.tokenFor("api.github.com")
        return get(
            "https://api.github.com/repos/${Urls.encodeSegment(owner)}/${Urls.encodeSegment(repo)}/readme",
            mapOf("Accept" to "application/vnd.github.raw", "X-GitHub-Api-Version" to "2022-11-28"),
            token?.let { "Bearer $it" },
        )
    }

    private fun gitlab(spec: SourceSpec): String? {
        val host = Urls.host(spec.url)
        val path = Urls.segments(spec.url).takeWhile { it != "-" }.joinToString("/").takeIf { it.isNotEmpty() } ?: return null
        val token = tokens.tokenFor(host)?.let { "Bearer $it" }
        val project = get("https://$host/api/v4/projects/${Urls.encodeSegment(path)}", authorization = token) ?: return null
        val readme = parsed { Json.parseObject(project).string("readme_url") } ?: return null
        // The page of the file becomes the file itself, on the same host and nowhere else.
        val raw = readme.replaceFirst("/-/blob/", "/-/raw/").takeIf { Urls.isHttps(it) && Urls.host(it) == host } ?: return null
        return get(raw, authorization = token)
    }

    private fun forgejo(spec: SourceSpec): String? {
        val host = Urls.host(spec.url)
        val (owner, repo) = ownerRepo(spec) ?: return null
        val token = tokens.tokenFor(host)
        val headers = token?.let { mapOf("Authorization" to "token $it") }.orEmpty()
        val base = "https://$host/api/v1/repos/${Urls.encodeSegment(owner)}/${Urls.encodeSegment(repo)}"
        for (name in README_NAMES) {
            val answer = get("$base/contents/${Urls.encodeSegment(name)}", headers) ?: continue
            val content = parsed { Json.parseObject(answer).string("content") } ?: continue
            return try {
                String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    private fun ownerRepo(spec: SourceSpec): Pair<String, String>? {
        val segments = Urls.segments(spec.url)
        if (segments.size < 2) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private inline fun <T> parsed(block: () -> T?): T? = try {
        block()
    } catch (_: JsonException) {
        null
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024
        private val README_NAMES = listOf("README.md", "readme.md", "Readme.md", "README")
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>")
        private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")

        /**
         * A README with the HTML a forge would draw taken out: badges, centred logos and comments
         * say nothing as text. Images keep their words, where they have any.
         */
        internal fun withoutHtml(markdown: String): String = markdown
            .replace(COMMENT, "")
            .replace(TAG, "")
            .replace(IMAGE) { it.groupValues[1] }
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
