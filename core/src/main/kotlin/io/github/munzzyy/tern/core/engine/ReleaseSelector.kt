package io.github.munzzyy.tern.core.engine

import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.core.version.Version

enum class Rejection { PRERELEASE, TAG_FILTER, TITLE_FILTER, NOTES_FILTER, TOO_NEW, SKIPPED, NO_USABLE_FILE, WRONG_PACKAGE }

data class Selection(
    /** The release to offer, with its version already run through the policy's extraction pattern. */
    val candidate: Release?,
    /** Why each passed-over release was passed over, newest first. */
    val rejected: List<Pair<Release, Rejection>>,
)

object ReleaseSelector {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** How many releases in a row get their package checked before giving up and taking one as-is, as today. */
    private const val MAX_PACKAGE_CHECKS = 4

    /**
     * [usable] says whether a release offers a file this device can install. [matchesPackage] says
     * whether one of its top-ranked files reads as the app this row tracks; it is only asked of the
     * first few usable releases, so a repository that publishes several apps from one feed (Bitwarden
     * and its authenticator, Thunderbird and K-9 Mail) does not stop at a release that only carries
     * the other app's file. The highest version wins rather than the most recent date, so a
     * maintenance release on an old branch does not displace a newer major version. Releases whose
     * tag carries no version, such as a rolling "latest", come after every versioned one, in the
     * order the source gave them.
     */
    fun select(
        releases: List<Release>,
        policy: ReleasePolicy,
        nowMs: Long,
        matchesPackage: (Release) -> Boolean = { true },
        usable: (Release) -> Boolean,
    ): Selection {
        val tag = SafePattern.compileOrNull(policy.tagFilter)
        val title = SafePattern.compileOrNull(policy.titleFilter)
        val notes = SafePattern.compileOrNull(policy.notesFilter)
        val extract = SafePattern.compileOrNull(policy.versionExtract)

        val rejected = ArrayList<Pair<Release, Rejection>>()
        val passed = ArrayList<Release>()
        SafePattern.watched("release filters") { filter(releases, policy, nowMs, tag, title, notes, extract, passed, rejected) }

        val ordered = order(passed)
        var packageChecksLeft = MAX_PACKAGE_CHECKS
        for ((index, release) in ordered.withIndex()) {
            val reason = when {
                !usable(release) -> Rejection.NO_USABLE_FILE
                packageChecksLeft > 0 && !matchesPackage(release).also { packageChecksLeft-- } -> Rejection.WRONG_PACKAGE
                else -> null
            }
            if (reason == null) return Selection(release, rejected)
            rejected.add(release to reason)
            if (!policy.fallbackToOlder && index == 0) break
        }
        return Selection(null, rejected)
    }

    private fun filter(
        releases: List<Release>,
        policy: ReleasePolicy,
        nowMs: Long,
        tag: SafePattern?,
        title: SafePattern?,
        notes: SafePattern?,
        extract: SafePattern?,
        passed: MutableList<Release>,
        rejected: MutableList<Pair<Release, Rejection>>,
    ) {
        for (original in releases) {
            val release = if (extract == null) original else original.copy(version = extract.extract(original.version) ?: original.version)
            val reason = when {
                !policy.includePrereleases && release.countsAsPrerelease -> Rejection.PRERELEASE
                tag != null && !tag.matches(release.id) -> Rejection.TAG_FILTER
                title != null && !title.matches(release.title) -> Rejection.TITLE_FILTER
                notes != null && !notes.matches(release.notes) -> Rejection.NOTES_FILTER
                policy.skippedReleaseId != null && policy.skippedReleaseId == release.id -> Rejection.SKIPPED
                tooNew(release, policy, nowMs) -> Rejection.TOO_NEW
                else -> null
            }
            if (reason == null) passed.add(release) else rejected.add(release to reason)
        }
    }

    private fun tooNew(release: Release, policy: ReleasePolicy, nowMs: Long): Boolean {
        if (policy.minAgeDays <= 0) return false
        val published = release.publishedAtMs ?: return false
        return nowMs - published < policy.minAgeDays * DAY_MS
    }

    private fun order(releases: List<Release>): List<Release> {
        val parsed = releases.map { it to Version.parse(it.version) }
        val versioned = parsed.withIndex().filter { it.value.second.isComparable }.sortedWith { a, b ->
            val byVersion = b.value.second.compareTo(a.value.second)
            if (byVersion != 0) byVersion else a.index.compareTo(b.index)
        }.map { it.value.first }
        return versioned + parsed.filter { !it.second.isComparable }.map { it.first }
    }
}
