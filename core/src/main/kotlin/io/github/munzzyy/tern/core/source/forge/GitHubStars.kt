package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonArray
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.text.Shown
import java.io.IOException

data class StarredRepo(
    val name: String,
    val owner: String,
    val description: String?,
    /** Always https://github.com/owner/name, rebuilt from checked names rather than taken from the answer. */
    val url: String,
    val stars: Int,
)

/** The repositories a GitHub user has starred, newest star first. */
object GitHubStars {
    const val MAX_REPOS = 300
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 3
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val API_HOST = "api.github.com"
    private val USER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")

    fun isValidUser(user: String): Boolean = USER.matches(user)

    @Throws(SourceException::class)
    fun list(user: String, context: CheckContext, limit: Int = MAX_REPOS): List<StarredRepo> {
        val name = user.trim().removePrefix("@")
        if (!isValidUser(name)) throw SourceException(SourceErrorKind.NOT_FOUND, "Not a GitHub user name: ${name.take(40)}")
        val wanted = limit.coerceIn(1, MAX_REPOS)
        val authorization = context.tokens.tokenFor(API_HOST)?.let { "Bearer $it" }
        val found = LinkedHashMap<String, StarredRepo>()
        for (page in 1..MAX_PAGES) {
            val items = page(name, page, authorization, context)
            for (obj in items.objects()) {
                val repo = repoOf(obj) ?: continue
                found.putIfAbsent(repo.url.lowercase(), repo)
                if (found.size >= wanted) return found.values.toList()
            }
            if (items.items.size < PER_PAGE) break
        }
        return found.values.toList()
    }

    private fun page(user: String, page: Int, authorization: String?, context: CheckContext): JsonArray {
        val url = "https://$API_HOST/users/$user/starred?per_page=$PER_PAGE&page=$page"
        val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        val response = try {
            context.http.execute(HttpRequest(url, headers = headers, authorization = authorization))
        } catch (e: RateLimitedException) {
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, e.message ?: "The connection failed", cause = e)
        }
        response.use {
            refuse(it, user, authorization != null)
            val text = try {
                it.text(MAX_BYTES)
            } catch (e: IOException) {
                throw SourceException(SourceErrorKind.NETWORK, e.message ?: "The answer was cut short", cause = e)
            }
            return try {
                Json.parseArray(text)
            } catch (e: JsonException) {
                throw SourceException(SourceErrorKind.PARSE, "GitHub's list of stars could not be read", cause = e)
            }
        }
    }

    private fun refuse(response: HttpResponse, user: String, withToken: Boolean) {
        if (response.isSuccess) return
        val remaining = response.headers["X-RateLimit-Remaining"]?.trim()
        if (response.status == 429 || (response.status == 403 && remaining == "0")) {
            val reset = response.headers["X-RateLimit-Reset"]?.trim()?.toLongOrNull()?.takeIf { it in 0..MAX_EPOCH_SECONDS }?.times(1000)
            throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by $API_HOST", reset)
        }
        when (response.status) {
            404 -> throw SourceException(SourceErrorKind.NOT_FOUND, "There is no GitHub user named $user")
            401, 403 -> throw SourceException(SourceErrorKind.AUTH, if (withToken) "GitHub rejected the saved token" else "GitHub refused the request")
            else -> throw SourceException(SourceErrorKind.NETWORK, "GitHub returned ${response.status}")
        }
    }

    private fun repoOf(obj: JsonObject): StarredRepo? {
        val name = obj.string("name")?.takeIf(RepoNames::isValid) ?: return null
        val owner = obj.obj("owner")?.string("login")?.takeIf(RepoNames::isValid) ?: return null
        val stars = obj.long("stargazers_count")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val description = Shown.lineOrNull(obj.string("description"), MAX_DESCRIPTION)
        return StarredRepo(name, owner, description, "https://github.com/$owner/$name", stars)
    }

    private const val MAX_DESCRIPTION = 500
    private const val MAX_EPOCH_SECONDS = 32_503_680_000L
}
