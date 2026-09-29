package io.github.munzzyy.tern.core.source

/** Values of SourceSpec.type and the option keys each type reads. Exports depend on these strings. */
object SourceTypes {
    const val GITHUB = "github"
    const val GITHUB_ACTIONS = "github-actions"
    const val GITLAB = "gitlab"
    const val FORGEJO = "forgejo"
    const val FDROID = "fdroid"
    const val FDROID_REPO = "fdroid-repo"
    const val HTML = "html"
    const val DIRECT = "direct"
    const val JENKINS = "jenkins"
    const val SOURCEHUT = "sourcehut"
    const val SOURCEFORGE = "sourceforge"
}

object SourceOptions {
    /** github-actions: workflow file name such as "build.yml". */
    const val WORKFLOW = "workflow"

    /** github-actions: branch whose successful runs are followed. */
    const val BRANCH = "branch"

    /** fdroid, fdroid-repo: application id inside the repository. */
    const val PACKAGE = "package"

    /** fdroid-repo: SHA-256 of the repository signing certificate, lowercase hex. */
    const val FINGERPRINT = "fingerprint"

    /** html: regular expression a link must match to count as a download. */
    const val LINK_FILTER = "linkFilter"

    /** html: JSON array of regular expressions, one per intermediate page to follow before the final one. */
    const val STEPS = "steps"

    /** html: "link" (default), "text" or "page": where the version is read from. */
    const val VERSION_FROM = "versionFrom"

    /** html: "version" (default) or "page": how candidate links are ordered. */
    const val SORT = "sort"
}
