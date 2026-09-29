package io.github.munzzyy.tern.fake

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.ChecksumState
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.SignerState

/** The invented links detect() knows, so screens and tests can walk every kind of answer. */
object FakeLinks {
    const val NEW_APP = "https://github.com/example/sparrow"

    /** Every suggestion of the stand-in lives under this address and is found when asked for. */
    const val SUGGESTED_PREFIX = "https://codeberg.org/suggested/"
    const val WARNED_APP = "https://codeberg.org/example/wren"
    const val TRACKED_APP = "https://github.com/example/trailmap"
    const val MISSING = "https://gitlab.com/example/missing"

    /** Comes with settings of its own, the way an Obtainium link or an import does. */
    const val CARRIED = "https://github.com/example/finch"

    /** A repository with releases but nothing an Android device can install, like most starred ones. */
    const val NO_FILE_PREFIX = "https://github.com/example/tool-"

    /** A repository given by its address, which answers with the apps it holds and says that there are more. */
    const val REPOSITORY = "https://apps.example.org/fdroid/repo"

    /** Where Harbor Terminal moved: the same app and signer, so following it works. */
    const val MOVED_HOME = "https://github.com/example-org/harborterm"

    /** Where Pocket Notes claims to have moved: a different signer, so following it is refused. */
    const val MOVED_ELSEWHERE = "https://github.com/someone-else/pocketnotes"
}

fun fakeDetect(input: String, invent: Invent): Detection {
    val text = input.trim()
    val lower = text.lowercase()
    return when {
        lower.startsWith("obtainium://") -> found(invent, "Imported Link App", SourceTypes.GITHUB, "linked", emptyList()).let {
            it.copy(carried = it.plain().copy(releases = ReleasePolicy(includePrereleases = true)))
        }
        lower.startsWith(FakeLinks.CARRIED) -> found(invent, "Finch", SourceTypes.GITHUB, "finch", emptyList()).let {
            it.copy(
                carried = it.plain().copy(
                    updates = UpdateMode.MANUAL,
                    releases = ReleasePolicy(
                        includePrereleases = true,
                        tagFilter = "^v[0-9]",
                        titleFilter = "stable",
                        notesFilter = "android",
                        versionExtract = "v(.+)",
                        minAgeDays = 3,
                    ),
                    assets = AssetPolicy(include = "universal", exclude = "debug"),
                    pinnedSigners = listOf(fakeHash("signer:carried:finch")),
                    trackOnly = false,
                ),
            )
        }
        lower.startsWith(FakeLinks.NEW_APP) -> found(invent, "Sparrow", SourceTypes.GITHUB, "sparrow", emptyList())
        lower.startsWith(FakeLinks.SUGGESTED_PREFIX) -> text.trimEnd('/').substringAfterLast('/').let { slug ->
            val suggested = FakeSuggestions.all.firstOrNull { it.url.endsWith("/$slug") }
            found(invent, suggested?.name ?: slug, SourceTypes.FORGEJO, slug, emptyList()).copy(builtInPin = suggested?.pinned == true)
        }
        lower.startsWith(FakeLinks.REPOSITORY) -> Detection.Results(
            text,
            listOf("Lantern Player", "Pocket Notes", "Tide Table").map { name ->
                val slug = name.lowercase().filter { it.isLetterOrDigit() }
                SearchHit(name, null, "An invented app of an invented repository.", FakeLinks.SUGGESTED_PREFIX + slug, "Example Apps")
            },
            more = true,
        )
        lower.startsWith(FakeLinks.WARNED_APP) -> found(
            invent, "Wren", SourceTypes.FORGEJO, "wren",
            listOf("The newest release is a pre-release. It is offered because it is the only one with a file.", "This project publishes no checksums, so only the signature can be checked."),
            checksum = ChecksumState.NOT_PUBLISHED, signer = SignerState.UNKNOWN,
        )
        lower.startsWith(FakeLinks.TRACKED_APP) -> found(invent, "Trail Map", SourceTypes.GITHUB, "trailmap", emptyList()).copy(alreadyTracked = "trailmap")
        lower.startsWith(FakeLinks.NO_FILE_PREFIX) -> found(invent, text.substringAfterLast('/'), SourceTypes.GITHUB, text.substringAfterLast('/'), listOf("None of its files can be installed on this device."))
            .copy(file = null, otherFiles = emptyList(), verification = null)
        lower.startsWith(FakeLinks.MISSING) -> Detection.Failed(Problem(ProblemKind.NOT_FOUND, "gitlab.com answered 404: there is no project at that address."))
        lower.startsWith("https://") -> Detection.Failed(Problem(ProblemKind.UNSUPPORTED, "Tern does not know how to find releases on that page."))
        lower.startsWith("http://") -> Detection.Failed(Problem(ProblemKind.UNSUPPORTED, "Only https links are accepted."))
        lower == "zzz" -> Detection.Results(text, emptyList())
        else -> Detection.Results(
            text,
            listOf(
                SearchHit("Sparrow", "example", "A small feed reader that works offline.", FakeLinks.NEW_APP, "GitHub", 1240),
                SearchHit("Wren", "example", "Notes that sync over your own server.", FakeLinks.WARNED_APP, "Codeberg", 88),
                SearchHit("Trail Map", "example", null, FakeLinks.TRACKED_APP, "GitHub", 1),
            ),
        )
    }
}

private fun Detection.Found.plain() = AppConfig(id = "", source = spec, name = name, author = author)

private fun found(
    invent: Invent,
    name: String,
    type: String,
    repo: String,
    warnings: List<String>,
    checksum: ChecksumState = ChecksumState.PENDING,
    signer: SignerState = SignerState.FIRST_SEEN,
): Detection.Found {
    val release = invent.release(repo, "2.0.1", 3, prerelease = warnings.size > 1, withSha = checksum != ChecksumState.NOT_PUBLISHED)
    val (file, others) = invent.choices(release)
    val spec: SourceSpec = invent.spec("example", repo, type)
    return Detection.Found(
        spec = spec,
        name = name,
        author = "Example Labs",
        description = "An invented app for trying out the add screen.",
        release = release,
        file = file,
        otherFiles = others,
        verification = invent.verification("org.example.$repo", signer, checksum, fileSha256 = file?.asset?.sha256),
        installed = null,
        alreadyTracked = null,
        warnings = warnings,
    )
}
