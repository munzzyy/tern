package io.github.munzzyy.tern.engine.real

import android.util.Log
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.data.SettingsStore
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/**
 * Searches the forges and every source that can be searched, those the person picked, all at
 * once. Each may fail without sinking the others, and one that fails is named with the reason.
 */
class Search(
    private val http: HttpClient,
    private val tokens: TokenProvider,
    private val stores: List<Searchable> = emptyList(),
    private val context: () -> CheckContext = { CheckContext(http, InMemoryValidatorStore(), tokens) },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** Every place a search can look, in the order their hits are shown. */
    val origins: List<String> = FORGES + stores.map { it.origin }

    /** The Forgejo or Gitea a search looks in, by its host, and the fewest stars a project on GitHub or a Forgejo may have. */
    data class Scope(val forgejo: String = Settings.DEFAULT_FORGEJO, val minStars: Int = 0)

    /** Why a place could not be searched. */
    enum class Why { UNREACHABLE, WAIT, REFUSED, STATUS, UNREADABLE }

    /** A place that could not be searched, by the name it is shown by. [status] goes with [Why.STATUS], [retryAtMs] with [Why.WAIT] when the place said. */
    data class Miss(val origin: String, val why: Why, val status: Int? = null, val retryAtMs: Long? = null)

    /** What a search found, the places taking turns with their best hits first, and the places it could not look in. */
    data class Outcome(val hits: List<SearchHit>, val missed: List<Miss>)

    private class Place(val hits: List<SearchHit>, val miss: Miss?)

    /** A place answered, and not with what was asked for. */
    private class Refused(val status: Int) : Exception("Answered $status")

    suspend fun search(query: String, within: Set<String> = FORGES.toSet(), scope: Scope = Scope()): Outcome = coroutineScope {
        val forgejo = SettingsStore.cleanHost(scope.forgejo)
        val forges = listOf(
            Triple("GitHub", "GitHub") { github(query, scope.minStars) },
            Triple(CODEBERG_PLACE, forgejoOrigin(forgejo)) { forgejo(query, forgejo, scope.minStars) },
            Triple("GitLab", "GitLab") { gitlab(query) },
        ).filter { it.first in within }.map { (_, origin, block) -> async { safely(origin, block) } }
        val others = stores.filter { it.origin in within }.map { store ->
            async { safely(store.origin) { store.search(query.take(MAX_QUERY), context()).take(PER_STORE).mapNotNull { hit(it, store.origin) } } }
        }
        val places = (forges + others).awaitAll()
        Outcome(takingTurns(places.map { it.hits }), places.mapNotNull { it.miss })
    }

    private suspend fun safely(origin: String, block: () -> List<SearchHit>): Place = try {
        Place(runInterruptible(Dispatchers.IO) { block() }, null)
    } catch (e: RateLimitedException) {
        missed(Miss(origin, Why.WAIT, retryAtMs = e.retryAtMs), "rate limited")
    } catch (e: java.io.IOException) {
        missed(Miss(origin, Why.UNREACHABLE), e.javaClass.simpleName)
    } catch (e: JsonException) {
        missed(Miss(origin, Why.UNREADABLE), "answer unreadable: ${e.message}")
    } catch (e: Refused) {
        missed(if (e.status == 401 || e.status == 403) Miss(origin, Why.REFUSED) else Miss(origin, Why.STATUS, status = e.status), "answered ${e.status}")
    } catch (e: SourceException) {
        val why = when (e.kind) {
            SourceErrorKind.RATE_LIMITED -> Why.WAIT
            SourceErrorKind.AUTH -> Why.REFUSED
            SourceErrorKind.NETWORK -> Why.UNREACHABLE
            else -> Why.UNREADABLE
        }
        missed(Miss(origin, why, retryAtMs = e.retryAtMs), e.message.orEmpty())
    }

    private fun missed(miss: Miss, detail: String): Place {
        log("${miss.origin} search failed: $detail")
        return Place(emptyList(), miss)
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

    private fun get(url: String, authorization: String? = null, headers: Map<String, String> = emptyMap()): String =
        http.execute(HttpRequest(url, headers = headers, authorization = authorization)).use {
            if (!it.isSuccess) throw Refused(it.status)
            it.text(MAX_BYTES)
        }

    /** GitHub leaves out what has too few stars itself, so the page holds as many hits as it can. */
    private fun github(query: String, minStars: Int): List<SearchHit> {
        val words = query.take(MAX_QUERY) + if (minStars > 0) " stars:>=$minStars" else ""
        val token = tokens.tokenFor("api.github.com")
        val text = get(
            "https://api.github.com/search/repositories?q=${Urls.encodeSegment(words)}&per_page=$PER_FORGE",
            token?.let { "Bearer $it" },
            mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
        )
        return Json.parseObject(text).array("items")?.objects().orEmpty().mapNotNull { repo ->
            hit(repo, "GitHub", repo.string("name"), repo.obj("owner")?.string("login"), repo.string("html_url"), repo.long("stargazers_count"))
        }
    }

    /** Codeberg, or the Forgejo or Gitea the person named. The token stored for exactly that host goes along, and to no other. */
    private fun forgejo(query: String, at: String, minStars: Int): List<SearchHit> {
        val token = tokens.tokenFor(Urls.host("https://$at"))
        val text = get("https://$at/api/v1/repos/search?q=${Urls.encodeSegment(query.take(MAX_QUERY))}&limit=$PER_FORGE", token?.let { "token $it" })
        // A project on a Forgejo of its own is read as one; Codeberg's addresses say so by themselves.
        val type = if (at == Settings.DEFAULT_FORGEJO) null else SourceTypes.FORGEJO
        return Json.parseObject(text).array("data")?.objects().orEmpty()
            .filter { (it.long("stars_count") ?: 0L) >= minStars }
            .mapNotNull { repo ->
                hit(repo, forgejoOrigin(at), repo.string("name"), repo.obj("owner")?.string("login"), repo.string("html_url"), repo.long("stars_count"))
                    ?.copy(type = type)
            }
    }

    private fun gitlab(query: String): List<SearchHit> {
        val text = get("https://gitlab.com/api/v4/projects?search=${Urls.encodeSegment(query.take(MAX_QUERY))}&per_page=$PER_GITLAB&order_by=star_count")
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

        /** The place the person picks for a search of a Forgejo, whichever Forgejo that is. */
        const val CODEBERG_PLACE = "Codeberg"
        private const val TAG = "TernSearch"
        private const val MAX_QUERY = 200

        /** As many as Obtainium asks GitHub and a Forgejo for. A Forgejo may hand out fewer. */
        private const val PER_FORGE = 100

        /** As many as Obtainium shows of gitlab.com, which is what it hands out unasked. */
        private const val PER_GITLAB = 20
        private const val PER_STORE = 50
        private const val MAX_BYTES = 2 * 1024 * 1024

        /** What a Forgejo's hits and failures are shown by: Codeberg by its name, any other by its host. */
        fun forgejoOrigin(at: String): String = if (at == Settings.DEFAULT_FORGEJO) CODEBERG_PLACE else at

        /** The hits of every place, each place's first, then each one's second, and so on, so that a long list leaves none out at its top. */
        internal fun <T> takingTurns(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>(lists.sumOf { it.size })
            var turn = 0
            while (true) {
                var any = false
                for (list in lists) {
                    if (turn < list.size) {
                        out += list[turn]
                        any = true
                    }
                }
                if (!any) return out
                turn++
            }
        }
    }
}
