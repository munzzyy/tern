package io.github.munzzyy.stamp.engine.real

import android.util.Log
import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonException
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.net.HttpClient
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.TokenProvider
import io.github.munzzyy.stamp.engine.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/** Searches GitHub, Codeberg and gitlab.com at once; each may fail without sinking the others. */
class Search(private val http: HttpClient, private val tokens: TokenProvider) {
    suspend fun search(query: String): List<SearchHit> = coroutineScope {
        val q = Urls.encodeSegment(query.take(MAX_QUERY))
        listOf(
            async { safely("GitHub") { github(q) } },
            async { safely("Codeberg") { codeberg(q) } },
            async { safely("GitLab") { gitlab(q) } },
        ).awaitAll().flatten()
    }

    private suspend fun safely(origin: String, block: () -> List<SearchHit>): List<SearchHit> = try {
        runInterruptible(Dispatchers.IO) { block() }
    } catch (e: java.io.IOException) {
        Log.i(TAG, "$origin search failed: ${e.javaClass.simpleName}")
        emptyList()
    } catch (e: JsonException) {
        Log.i(TAG, "$origin search answer unreadable: ${e.message}")
        emptyList()
    }

    private fun get(url: String, authorization: String? = null, headers: Map<String, String> = emptyMap()): String? =
        http.execute(HttpRequest(url, headers = headers, authorization = authorization)).use { if (it.isSuccess) it.text(MAX_BYTES) else null }

    private fun github(q: String): List<SearchHit> {
        val token = tokens.tokenFor("api.github.com")
        val text = get(
            "https://api.github.com/search/repositories?q=$q&per_page=$PER_SOURCE",
            token?.let { "Bearer $it" },
            mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
        ) ?: return emptyList()
        return Json.parseObject(text).array("items")?.objects().orEmpty().mapNotNull { repo ->
            hit(repo, "GitHub", repo.string("name"), repo.obj("owner")?.string("login"), repo.string("html_url"), repo.long("stargazers_count"))
        }
    }

    private fun codeberg(q: String): List<SearchHit> {
        val text = get("https://codeberg.org/api/v1/repos/search?q=$q&limit=$PER_SOURCE") ?: return emptyList()
        return Json.parseObject(text).array("data")?.objects().orEmpty().mapNotNull { repo ->
            hit(repo, "Codeberg", repo.string("name"), repo.obj("owner")?.string("login"), repo.string("html_url"), repo.long("stars_count"))
        }
    }

    private fun gitlab(q: String): List<SearchHit> {
        val text = get("https://gitlab.com/api/v4/projects?search=$q&per_page=$PER_SOURCE&order_by=star_count") ?: return emptyList()
        return Json.parseArray(text).objects().mapNotNull { project ->
            hit(project, "GitLab", project.string("name"), project.obj("namespace")?.string("full_path"), project.string("web_url"), project.long("star_count"))
        }
    }

    private fun hit(obj: JsonObject, origin: String, name: String?, owner: String?, url: String?, stars: Long?): SearchHit? {
        val safeUrl = url?.takeIf { Urls.isHttps(it) }?.let(Urls::normalize) ?: return null
        return SearchHit(
            name = name?.take(200) ?: return null,
            owner = owner?.take(200),
            description = obj.string("description")?.take(500),
            url = safeUrl,
            origin = origin,
            stars = stars?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
        )
    }

    private companion object {
        const val TAG = "StampSearch"
        const val MAX_QUERY = 200
        const val PER_SOURCE = 10
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
