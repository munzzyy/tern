package io.github.munzzyy.stamp.core.source.forge

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.json.JsonException
import io.github.munzzyy.stamp.core.json.JsonObject
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.source.CheckContext
import java.io.IOException

/**
 * Where a forge keeps a picture for a project. The addresses for GitHub and GitLab were measured
 * against the services; Forgejo's come from the fields its API documents.
 */
internal object ForgeIcons {
    /** Where the fastlane convention keeps the icon of a store listing inside a repository. */
    const val STORE_ICON = "fastlane/metadata/android/en-US/images/icon.png"

    private const val AVATAR_SIDE = 192
    private const val MAX_ANSWER = 256 * 1024

    /** GitHub has no picture for a repository: the store listing first, the owner's avatar second. */
    fun gitHub(owner: String, repo: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$owner/$repo/HEAD/$STORE_ICON",
        "https://avatars.githubusercontent.com/$owner?s=$AVATAR_SIDE",
    )

    /** The project's avatar, then its store listing, then the avatar of the group or user it belongs to. */
    fun gitLab(at: String, projectPath: String, context: CheckContext, authorization: String?): List<String?> {
        val project = ask("https://$at/api/v4/projects/${Urls.encodeSegment(projectPath)}", context, authorization)
        return listOf(
            address(at, project?.string("avatar_url")),
            "https://$at/$projectPath/-/raw/HEAD/$STORE_ICON",
            address(at, project?.obj("namespace")?.string("avatar_url")),
        )
    }

    /** The repository's avatar, then its owner's. */
    fun forgejo(at: String, owner: String, repo: String, context: CheckContext, authorization: String?): List<String?> {
        val repository = ask("https://$at/api/v1/repos/$owner/$repo", context, authorization)
        return listOf(
            address(at, repository?.string("avatar_url")),
            address(at, repository?.obj("owner")?.string("avatar_url")),
        )
    }

    /** GitLab names some avatars by path alone. */
    private fun address(at: String, named: String?): String? {
        val text = named?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.startsWith("/") && !text.startsWith("//")) "https://$at$text" else text
    }

    /** An icon is never worth a failed check, so every failure here means no answer. */
    private fun ask(url: String, context: CheckContext, authorization: String?): JsonObject? = try {
        context.http.execute(HttpRequest(url, authorization = authorization)).use { response ->
            if (response.isSuccess) Json.parse(response.text(MAX_ANSWER)) as? JsonObject else null
        }
    } catch (_: IOException) {
        null
    } catch (_: JsonException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
