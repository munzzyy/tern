package io.github.munzzyy.jackdaw.fake

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.SearchHit
import io.github.munzzyy.jackdaw.engine.SignerState

/** The invented links detect() knows, so screens and tests can walk every kind of answer. */
object FakeLinks {
    const val NEW_APP = "https://github.com/example/sparrow"
    const val WARNED_APP = "https://codeberg.org/example/wren"
    const val TRACKED_APP = "https://github.com/example/trailmap"
    const val MISSING = "https://gitlab.com/example/missing"
}

fun fakeDetect(input: String, invent: Invent): Detection {
    val text = input.trim()
    val lower = text.lowercase()
    return when {
        lower.startsWith("obtainium://") -> found(invent, "Imported Link App", SourceTypes.GITHUB, "linked", listOf("Settings carried in the link were applied. Check them after adding."))
        lower.startsWith(FakeLinks.NEW_APP) -> found(invent, "Sparrow", SourceTypes.GITHUB, "sparrow", emptyList())
        lower.startsWith(FakeLinks.WARNED_APP) -> found(
            invent, "Wren", SourceTypes.FORGEJO, "wren",
            listOf("The newest release is a pre-release. It is offered because it is the only one with a file.", "This project publishes no checksums, so only the signature can be checked."),
            checksum = ChecksumState.NOT_PUBLISHED, signer = SignerState.UNKNOWN,
        )
        lower.startsWith(FakeLinks.TRACKED_APP) -> found(invent, "Trail Map", SourceTypes.GITHUB, "trailmap", emptyList()).copy(alreadyTracked = "trailmap")
        lower.startsWith(FakeLinks.MISSING) -> Detection.Failed(Problem(ProblemKind.NOT_FOUND, "gitlab.com answered 404: there is no project at that address."))
        lower.startsWith("https://") -> Detection.Failed(Problem(ProblemKind.UNSUPPORTED, "Jackdaw does not know how to find releases on that page."))
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
        verification = invent.verification("org.example.$repo", signer, checksum),
        installed = null,
        alreadyTracked = null,
        warnings = warnings,
    )
}
