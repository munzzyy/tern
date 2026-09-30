package io.github.munzzyy.tern.core.engine

import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.VersionFrom
import io.github.munzzyy.tern.core.text.MatchTemplate
import io.github.munzzyy.tern.core.text.NaturalOrder
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.core.version.Version
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Rejection { PRERELEASE, TAG_FILTER, TITLE_FILTER, NOTES_FILTER, TOO_NEW, SKIPPED, NO_USABLE_FILE, WRONG_PACKAGE, VERSION_FILTER, STAY_BEHIND }

data class Selection(
    /** The release to offer, with its version already read the way the policy says and run through its extraction pattern. */
    val candidate: Release?,
    /** Why each passed-over release was passed over, newest first. */
    val rejected: List<Pair<Release, Rejection>>,
)

object ReleaseSelector {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** How many releases in a row get their package checked before giving up and taking one as-is, as today. */
    private const val MAX_PACKAGE_CHECKS = 4

    const val MAX_STAY_BEHIND = 5

    private val DATE_VERSION = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmm", Locale.ROOT).withZone(ZoneOffset.UTC)

    private class Filters(
        val tag: SafePattern?,
        val title: SafePattern?,
        val notes: SafePattern?,
        val extract: SafePattern?,
        val template: MatchTemplate?,
        val version: SafePattern?,
    )

    /**
     * [usable] says whether a release offers a file this device can install. [matchesPackage] says
     * whether one of its top-ranked files reads as the app this row tracks; it is only asked of the
     * first few usable releases, so a repository that publishes several apps from one feed (Bitwarden
     * and its authenticator, Thunderbird and K-9 Mail) does not stop at a release that only carries
     * the other app's file. By default the highest version wins rather than the most recent date, so
     * a maintenance release on an old branch does not displace a newer major version. Releases whose
     * tag carries no version, such as a rolling "latest", come after every versioned one, in the
     * order the source gave them. [ReleasePolicy.order] can order them otherwise, and a release the
     * source marks as latest comes first in every order.
     */
    fun select(
        releases: List<Release>,
        policy: ReleasePolicy,
        nowMs: Long,
        matchesPackage: (Release) -> Boolean = { true },
        usable: (Release) -> Boolean,
    ): Selection {
        val filters = Filters(
            tag = SafePattern.compileOrNull(policy.tagFilter),
            title = SafePattern.compileOrNull(policy.titleFilter),
            notes = SafePattern.compileOrNull(policy.notesFilter),
            extract = SafePattern.compileOrNull(policy.versionExtract),
            template = MatchTemplate.parse(policy.matchGroup),
            version = SafePattern.compileOrNull(policy.versionFilter),
        )

        val rejected = ArrayList<Pair<Release, Rejection>>()
        val passed = ArrayList<Release>()
        SafePattern.watched("release filters") { filter(releases, policy, nowMs, filters, passed, rejected) }

        val ordered = order(passed, policy.order)
        var packageChecksLeft = MAX_PACKAGE_CHECKS
        var stayBehindLeft = policy.stayBehind.coerceIn(0, MAX_STAY_BEHIND)
        var stayedBehind = 0
        for ((index, release) in ordered.withIndex()) {
            val reason = when {
                !usable(release) -> Rejection.NO_USABLE_FILE
                packageChecksLeft > 0 && !matchesPackage(release).also { packageChecksLeft-- } -> Rejection.WRONG_PACKAGE
                stayBehindLeft > 0 -> Rejection.STAY_BEHIND.also { stayBehindLeft-- }
                else -> null
            }
            if (reason == null) return Selection(release, rejected)
            rejected.add(release to reason)
            if (reason == Rejection.STAY_BEHIND) {
                stayedBehind++
                continue
            }
            if (!policy.fallbackToOlder && index == stayedBehind) break
        }
        return Selection(null, rejected)
    }

    private fun filter(
        releases: List<Release>,
        policy: ReleasePolicy,
        nowMs: Long,
        filters: Filters,
        passed: MutableList<Release>,
        rejected: MutableList<Pair<Release, Rejection>>,
    ) {
        for (original in releases) {
            val release = withVersion(original, policy.versionFrom, filters)
            val reason = when {
                !policy.includePrereleases && release.countsAsPrerelease -> Rejection.PRERELEASE
                filters.tag != null && !filters.tag.matches(release.id) -> Rejection.TAG_FILTER
                filters.title != null && !filters.title.matches(titleOf(original)) -> Rejection.TITLE_FILTER
                filters.notes != null && !filters.notes.matches(release.notes) -> Rejection.NOTES_FILTER
                filters.version != null && !filters.version.matches(release.version) -> Rejection.VERSION_FILTER
                policy.skippedReleaseId != null && policy.skippedReleaseId == release.id -> Rejection.SKIPPED
                tooNew(release, policy, nowMs) -> Rejection.TOO_NEW
                else -> null
            }
            if (reason == null) passed.add(release) else rejected.add(release to reason)
        }
    }

    /** The version read from where the policy says, then run through its extraction pattern; the old one where that gives nothing. */
    private fun withVersion(release: Release, from: VersionFrom, filters: Filters): Release {
        val raw = when (from) {
            VersionFrom.TAG -> release.version
            VersionFrom.TITLE -> release.title?.trim()?.takeIf { it.isNotEmpty() } ?: release.version
            VersionFrom.DATE -> release.publishedAtMs?.let { DATE_VERSION.format(Instant.ofEpochMilli(it)) } ?: release.version
        }
        val extract = filters.extract
        val version = when {
            extract == null -> raw
            filters.template == null -> extract.extract(raw) ?: raw
            else -> extract.extract(raw, filters.template) ?: raw
        }
        return if (version == release.version) release else release.copy(version = version)
    }

    /**
     * What the title filter reads: the title, or where a release has none the version the source
     * gave, which on a forge is the tag. GitHub's releases need not have a name, and Obtainium
     * filters those by their tag.
     */
    private fun titleOf(release: Release): String = release.title?.trim()?.takeIf { it.isNotEmpty() } ?: release.version

    private fun tooNew(release: Release, minAgeDays: Int, nowMs: Long): Boolean {
        if (minAgeDays <= 0) return false
        val published = release.publishedAtMs ?: return false
        return nowMs - published < minAgeDays * DAY_MS
    }

    private fun tooNew(release: Release, policy: ReleasePolicy, nowMs: Long): Boolean = tooNew(release, policy.minAgeDays ?: 0, nowMs)

    /**
     * The releases a check [found], and while every one of them is too young for [minAgeDays], the
     * ones listed [before] that were old enough and that the source no longer lists. A store that
     * names only its newest release would otherwise leave nothing to offer until that one is old
     * enough. Obtainium keeps offering the last release it had in the same way.
     */
    fun keptUntilOldEnough(found: List<Release>, before: List<Release>, minAgeDays: Int, nowMs: Long): List<Release> {
        if (minAgeDays <= 0 || found.isEmpty() || found.any { !tooNew(it, minAgeDays, nowMs) }) return found
        val listed = found.mapTo(HashSet()) { it.id }
        return found + before.filter { it.id !in listed && !tooNew(it, minAgeDays, nowMs) }
    }

    private fun order(releases: List<Release>, order: ReleaseOrder): List<Release> {
        val sorted = when (order) {
            ReleaseOrder.VERSION -> byVersion(releases)
            ReleaseOrder.DATE -> releases.sortedWith(compareBy<Release> { it.publishedAtMs == null }.thenByDescending { it.publishedAtMs ?: 0L })
            ReleaseOrder.SOURCE -> releases
            ReleaseOrder.NAME -> releases.sortedWith { a, b -> NaturalOrder.compare(b.version, a.version) }
        }
        val (latest, rest) = sorted.partition { it.latest }
        return latest + rest
    }

    private fun byVersion(releases: List<Release>): List<Release> {
        val parsed = releases.map { it to Version.parse(it.version) }
        val versioned = parsed.withIndex().filter { it.value.second.isComparable }.sortedWith { a, b ->
            val byVersion = b.value.second.compareTo(a.value.second)
            if (byVersion != 0) byVersion else a.index.compareTo(b.index)
        }.map { it.value.first }
        return versioned + parsed.filter { !it.second.isComparable }.map { it.first }
    }
}
