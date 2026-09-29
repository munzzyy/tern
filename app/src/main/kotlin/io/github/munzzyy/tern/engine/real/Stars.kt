package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.forge.GitHubStars
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemException
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class Stars(private val e: RealEngine) {
    suspend fun starredBy(user: String): List<SearchHit> {
        val name = user.trim().removePrefix("@")
        if (!GitHubStars.isValidUser(name)) throw ProblemException(Problem(ProblemKind.NOT_FOUND, e.texts.starsBadName()))
        val context = CheckContext(e.http, InMemoryValidatorStore(), e.tokens, e.nowMs, e.device.profile)
        val repos = try {
            runInterruptible(Dispatchers.IO) { GitHubStars.list(name, context) }
        } catch (ex: SourceException) {
            val problem = if (ex.kind == SourceErrorKind.NOT_FOUND) Problem(ProblemKind.NOT_FOUND, e.texts.starsNoUser(name)) else e.checks.problemOf(ex)
            throw ProblemException(problem)
        }
        return repos.map { SearchHit(name = it.name, owner = it.owner, description = it.description, url = it.url, origin = ORIGIN, stars = it.stars) }
    }

    private companion object {
        const val ORIGIN = "GitHub"
    }
}
