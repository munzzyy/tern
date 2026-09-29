package io.github.munzzyy.jackdaw.core.source.forge

/** Owner and repository names as GitHub, GitLab and Forgejo all accept them. */
internal object RepoNames {
    private val VALID = Regex("^[A-Za-z0-9_.-]{1,100}$")

    fun isValid(name: String): Boolean = VALID.matches(name)
}
