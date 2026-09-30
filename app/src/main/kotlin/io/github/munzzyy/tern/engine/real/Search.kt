package io.github.munzzyy.tern.engine.real

import android.util.Log
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.engine.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/**
 * Searches the forges and every source that can be searched, those the person picked, all at
 * once; each may fail without sinking the others.
 */
class Search(
    private val http: HttpClient,
    private val tokens: TokenProvider,
    private val stores: List<Searchable> = emptyList(),
    private val context: () -> CheckContext = { CheckContext(http, InMemoryValidatorStore(), tokens) },
) {
    /** Every place a search can look, in the order their hits are shown. */
    val origins: List<String> = FORGES + stores.map { it.origin }

    suspend fun search(query: String, within: Set<String> = FORGES.toSet()): List<SearchHit> = coroutineScope {
        val q = Urls.encodeSegment(query.take(MAX_QUERY))
        val forges = listOf(
            "GitHub" to { github(q) },
            "Codeberg" to { codeberg(q) },
            "GitLab" to { gitlab(q) },
        ).filter { it.first in within }.map { (origin, block) -> async { safely(origin, block) } }
        val others = stores.filter { it.origin in within }.map { store ->
            async { safely(store.origin) { store.search(query.take(MAX_QUERY), context()).take(PER_STORE).mapNotNull { hit(it, store.origin) } } }
        }
        (forges + others).awaitAll().flatten()
    }

    private suspend fun safely(origin: String, block: () -> List<SearchHit>): List<SearchHit> = try {
        runInterruptible(Dispatchers.IO) { block() }
    } catch (e: java.io.IOException) {
        Log.i(TAG, "$origin search failed: ${e.javaClass.simpleName}")
        emptyList()
    } catch (e: JsonException) {
        Log.i(TAG, "$origin search answer unreadable: ${e.message}")
        emptyList()
    } catch (e: SourceException) {
        Log.i(TAG, "$origin search failed: ${e.message}")
        emptyList()
    }

    /** A hit of a store, cleaned as the forges' are. */
    internal fun hit(found: Hit, origin: String): SearchHit? {
        val safeUrl = found.url.takeIf { Urls.isHttps(it) }?.let(Urls::normalize) ?: return null
        return SearchHit(
            name = Shown.lineOrNull(found.name, 200) ?: return null,
            owner = Shown.lineOrNull(found.owner, 200),
            description = Shown.lineOrNull(found.description, 500),
            url = safeUrl,
            origin = origin,
            stars = found.stars?.coerceAtLeast(0),
        )
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

    internal fun hit(obj: JsonObject, origin: String, name: String?, owner: String?, url: String?, stars: Long?): SearchHit? {
        val safeUrl = url?.takeIf { Urls.isHttps(it) }?.let(Urls::normalize) ?: return null
        return SearchHit(
            name = Shown.lineOrNull(name, 200) ?: return null,
            owner = Shown.lineOrNull(owner, 200),
            description = Shown.lineOrNull(obj.string("description"), 500),
            url = safeUrl,
            origin = origin,
            stars = stars?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
        )
    }

    companion object {
        /** The forges Tern searches by itself, and by default. */
        val FORGES = listOf("GitHub", "Codeberg", "GitLab")
        private const val TAG = "TernSearch"
        private const val MAX_QUERY = 200
        private const val PER_SOURCE = 10
        private const val PER_STORE = 15
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
