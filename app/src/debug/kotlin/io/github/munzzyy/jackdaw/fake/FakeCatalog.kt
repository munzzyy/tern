package io.github.munzzyy.jackdaw.fake

import io.github.munzzyy.jackdaw.core.engine.InstalledApp
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.ChecksumState
import io.github.munzzyy.jackdaw.engine.Event
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.engine.FileChoice
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.Progress
import io.github.munzzyy.jackdaw.engine.SignerState
import io.github.munzzyy.jackdaw.engine.Verification
import java.security.MessageDigest

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** A made-up but well-formed SHA-256, stable for the same seed. */
fun fakeHash(seed: String): String =
    MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

class Invent(private val now: Long) {
    fun spec(owner: String, repo: String, type: String = SourceTypes.GITHUB): SourceSpec = when (type) {
        SourceTypes.GITHUB -> SourceSpec(type, "https://github.com/$owner/$repo")
        SourceTypes.GITLAB -> SourceSpec(type, "https://gitlab.com/$owner/$repo")
        SourceTypes.FORGEJO -> SourceSpec(type, "https://codeberg.org/$owner/$repo")
        SourceTypes.FDROID -> SourceSpec(type, "https://f-droid.org/packages/org.example.$repo")
        else -> SourceSpec(type, "https://downloads.example.org/$owner/$repo")
    }

    fun apk(repo: String, version: String, abi: String = "arm64-v8a", withSha: Boolean = true) = Asset(
        name = "$repo-${version.ifBlank { "latest" }}-$abi.apk",
        url = "https://downloads.example.org/$repo/${version.ifBlank { "latest" }}/$repo-$abi.apk",
        size = 8_000_000L + (repo.length * 731_117L) % 40_000_000L,
        sha256 = if (withSha) fakeHash("$repo$version$abi") else null,
    )

    fun release(repo: String, version: String, daysAgo: Int, prerelease: Boolean = false, withSha: Boolean = true) = Release(
        id = "v$version",
        version = version,
        title = "$repo $version",
        publishedAtMs = now - daysAgo * DAY,
        prerelease = prerelease,
        pageUrl = "https://downloads.example.org/$repo/releases/v$version",
        assets = listOf(
            apk(repo, version, "arm64-v8a", withSha),
            apk(repo, version, "armeabi-v7a", withSha),
            apk(repo, version, "x86_64", withSha),
            Asset("SHA256SUMS", "https://downloads.example.org/$repo/$version/SHA256SUMS", 400),
        ),
    )

    fun choices(release: Release): Pair<FileChoice?, List<FileChoice>> {
        val apks = release.installable
        if (apks.isEmpty()) return null to emptyList()
        val best = apks.firstOrNull { "x86_64" in it.name } ?: apks.first()
        val first = FileChoice(best, listOf("matches this device (x86_64)", "the only universal build is missing, so the smallest match wins"))
        val rest = apks.filter { it != best }.map { FileChoice(it, listOf("built for another processor (${it.name.substringAfterLast('-').removeSuffix(".apk")})")) }
        return first to rest
    }

    fun verification(
        pkg: String?,
        signer: SignerState,
        checksum: ChecksumState,
        verified: Boolean = false,
        newPermissions: List<String> = emptyList(),
    ) = Verification(
        packageName = pkg,
        signers = if (signer == SignerState.UNKNOWN) emptyList() else listOf(fakeHash("signer:$pkg:$signer")),
        signersVerified = verified,
        signerState = signer,
        checksum = checksum,
        checksumSource = when (checksum) {
            ChecksumState.NOT_PUBLISHED -> null
            else -> "GitHub release digest"
        },
        newPermissions = newPermissions,
    )

    fun row(
        id: String,
        name: String,
        status: AppStatus,
        installed: String?,
        offered: String?,
        author: String? = "Example Labs",
        type: String = SourceTypes.GITHUB,
        certain: Boolean = true,
        trackOnly: Boolean = false,
        signer: SignerState = SignerState.MATCHES_INSTALLED,
        checksum: ChecksumState = ChecksumState.MATCHED,
        problem: Problem? = null,
        progress: Progress? = null,
        newPermissions: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        pins: List<String> = emptyList(),
        withSha: Boolean = true,
        checkedHoursAgo: Int = 2,
        silent: Boolean? = true,
    ): AppRow {
        val pkg = "org.example.$id"
        val release = offered?.let { release(id, it, (id.length % 9) + 1, withSha = withSha) }
        val (file, others) = release?.let { choices(it) } ?: (null to emptyList())
        return AppRow(
            config = AppConfig(
                id = id,
                source = spec("example", id, type),
                name = name,
                author = author,
                packageName = pkg,
                trackOnly = trackOnly,
                categories = categories,
                pinnedSigners = pins,
            ),
            installed = installed?.let { InstalledApp(pkg, it, versionCode(it), listOf(fakeHash("signer:$pkg:installed"))) },
            status = status,
            statusCertain = certain,
            latest = release,
            file = file,
            otherFiles = others,
            verification = release?.let { verification(pkg, signer, checksum, newPermissions = newPermissions) },
            progress = progress,
            problem = problem,
            lastCheckedMs = now - checkedHoursAgo * HOUR,
            silentUpdate = if (installed != null) silent else null,
        )
    }

    fun versionCode(version: String): Long =
        version.split('.').mapNotNull { it.takeWhile(Char::isDigit).toLongOrNull() }.fold(0L) { acc, n -> acc * 1000 + n }

    fun defaultRows(): List<AppRow> = listOf(
        row("pocketnotes", "Pocket Notes", AppStatus.UP_TO_DATE, "2.3.1", "2.3.1", categories = listOf("Writing")),
        row(
            "trailmap", "Trail Map", AppStatus.UPDATE_AVAILABLE, "1.4.2", "1.5.0",
            newPermissions = listOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"),
            categories = listOf("Outdoors"), silent = false,
        ),
        row(
            "loomreader", "Loom Reader", AppStatus.UPDATE_AVAILABLE, "0.9", "0.10", certain = false,
            checksum = ChecksumState.NOT_PUBLISHED, withSha = false, type = SourceTypes.FORGEJO, categories = listOf("Writing"),
        ),
        row(
            "kestrelmail", "Kestrel Mail", AppStatus.NOT_INSTALLED, null, "3.0.0",
            signer = SignerState.FIRST_SEEN, checksum = ChecksumState.PENDING, type = SourceTypes.GITLAB,
        ),
        row("quietclock", "Quiet Clock", AppStatus.NEW_RELEASE, "5.1", "5.2", trackOnly = true, signer = SignerState.UNKNOWN),
        row(
            "ferrybook", "Ferry Book", AppStatus.BLOCKED, "4.0.0", "4.1.0", signer = SignerState.MISMATCH,
            problem = Problem(ProblemKind.SIGNER_MISMATCH, "The new file is signed with a different certificate than Ferry Book on this phone."),
        ),
        row(
            "lanternpdf", "Lantern PDF", AppStatus.BLOCKED, "2.2", "2.3", checksum = ChecksumState.MISMATCH,
            problem = Problem(ProblemKind.CHECKSUM_MISMATCH, "The downloaded file does not match the checksum the publisher gave."),
        ),
        row(
            "orbitweather", "Orbit Weather", AppStatus.BLOCKED, "7.0", "7.1",
            problem = Problem(ProblemKind.PACKAGE_MISMATCH, "The file is org.example.orbitlite, not org.example.orbitweather."),
        ),
        row(
            "saltcalc", "Salt Calculator", AppStatus.BLOCKED, "3.4", "3.2",
            problem = Problem(ProblemKind.DOWNGRADE, "Version 3.2 is older than the installed 3.4."),
        ),
        row(
            "ridgeradio", "Ridge Radio", AppStatus.ERROR, "1.0", null,
            problem = Problem(ProblemKind.RATE_LIMITED, "GitHub is limiting requests from this network.", retryAtMs = now + 37 * 60_000),
        ),
        row(
            "cinderplayer", "Cinder Player", AppStatus.UPDATE_AVAILABLE, "8.0", "8.1",
            progress = Progress(Phase.DOWNLOADING, 3_000_000, 24_000_000),
        ),
        row("mossgallery", "Moss Gallery", AppStatus.UPDATE_AVAILABLE, "1.1", "1.2", progress = Progress(Phase.VERIFYING)),
        row("tidetable", "Tide Table", AppStatus.UPDATE_AVAILABLE, "2.0", "2.1", progress = Progress(Phase.INSTALLING)),
        row("brushdraw", "Brush Draw", AppStatus.UPDATE_AVAILABLE, "0.5", "0.6", progress = Progress(Phase.WAITING_FOR_USER)),
        row(
            "vaultkeys", "Vault Keys", AppStatus.UPDATE_AVAILABLE, "12.0", "12.1", signer = SignerState.MATCHES_PIN,
            pins = listOf(fakeHash("signer:org.example.vaultkeys:MATCHES_PIN")), categories = listOf("Security"),
        ),
        row(
            "longname", "The Extraordinarily Long Named Offline Field Recorder And Transcriber For Birdwatchers",
            AppStatus.UP_TO_DATE, "1.0.0", "1.0.0", author = "A developer with a rather long display name as well",
        ),
        row("arnotes", "مفكرة الجيب", AppStatus.UPDATE_AVAILABLE, "1.2", "1.3", author = "مختبر المثال"),
        row("hecal", "לוח שנה פשוט", AppStatus.UP_TO_DATE, "4.0", "4.0"),
        row("harborterm", "Harbor Terminal", AppStatus.UPDATE_AVAILABLE, "29.0.0", "30.0.0", categories = listOf("Tools")),
        row("tagged", "Tagged Weather", AppStatus.UP_TO_DATE, "0.4.4", "v0.4.4"),
        row("nightlypad", "Nightly Pad", AppStatus.UPDATE_AVAILABLE, "1.4.2", "", type = "html", certain = false),
        row("latestapk", "Latest Build Viewer", AppStatus.NOT_INSTALLED, null, "", type = "html", signer = SignerState.FIRST_SEEN, checksum = ChecksumState.NOT_PUBLISHED, withSha = false),
        row("fieldguide", "Field Guide", AppStatus.UNKNOWN, null, null, checkedHoursAgo = 0).copy(lastCheckedMs = null),
    )

    /** What a real engine shows after checks ran without a connection: some rows failed on the network. */
    fun offlineRows(): List<AppRow> = defaultRows().map { r ->
        if (r.id in setOf("pocketnotes", "harborterm", "kestrelmail")) {
            r.copy(status = AppStatus.ERROR, problem = Problem(ProblemKind.NETWORK, "Could not reach ${r.config.source.url.substringAfter("://").substringBefore('/')}."))
        } else {
            r
        }
    }

    fun errorRows(): List<AppRow> = ProblemKind.entries.mapIndexed { i, kind ->
        val blocked = kind in setOf(
            ProblemKind.CHECKSUM_MISMATCH, ProblemKind.SIGNER_MISMATCH, ProblemKind.PIN_MISMATCH, ProblemKind.PACKAGE_MISMATCH,
            ProblemKind.DOWNGRADE, ProblemKind.NO_FILE_FOR_DEVICE, ProblemKind.UNSUPPORTED,
        )
        val words = kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        row(
            id = "err${kind.name.lowercase()}",
            name = "$words case",
            status = if (blocked) AppStatus.BLOCKED else AppStatus.ERROR,
            installed = if (i % 3 == 0) null else "1.$i",
            offered = if (blocked) "2.$i" else null,
            signer = if (kind == ProblemKind.SIGNER_MISMATCH || kind == ProblemKind.PIN_MISMATCH) SignerState.MISMATCH else SignerState.MATCHES_INSTALLED,
            checksum = if (kind == ProblemKind.CHECKSUM_MISMATCH) ChecksumState.MISMATCH else ChecksumState.MATCHED,
            problem = Problem(kind, problemMessage(kind), retryAtMs = if (kind == ProblemKind.RATE_LIMITED) now + 12 * 60_000 else null),
        )
    }

    private fun problemMessage(kind: ProblemKind): String = when (kind) {
        ProblemKind.NETWORK -> "The connection to the source timed out."
        ProblemKind.RATE_LIMITED -> "GitHub is limiting requests from this network."
        ProblemKind.NOT_FOUND -> "The project page answered 404 Not Found."
        ProblemKind.AUTH -> "The saved token for api.github.com was refused."
        ProblemKind.PARSE -> "The release list was not valid JSON."
        ProblemKind.NO_RELEASES -> "The project has no releases yet."
        ProblemKind.NO_FILE_FOR_DEVICE -> "No file is built for x86_64."
        ProblemKind.CHECKSUM_MISMATCH -> "The downloaded file does not match the published checksum."
        ProblemKind.SIGNER_MISMATCH -> "The file is signed by a different certificate than the installed app."
        ProblemKind.PIN_MISMATCH -> "The file is signed by a different certificate than the one pinned for this app."
        ProblemKind.PACKAGE_MISMATCH -> "The file is a different app: org.example.other."
        ProblemKind.DOWNGRADE -> "The offered version is older than the installed one."
        ProblemKind.INSTALL_FAILED -> "Android said: the package conflicts with an existing package."
        ProblemKind.STORAGE -> "Only 40 MB free; the file needs 120 MB."
        ProblemKind.UNSUPPORTED -> "The app needs Android 17."
    }

    fun manyRows(count: Int): List<AppRow> {
        val words = listOf("Amber", "Birch", "Cobalt", "Dune", "Ember", "Fjord", "Garnet", "Heath", "Iris", "Juniper", "Kelp", "Loam")
        val kinds = listOf("Notes", "Maps", "Player", "Reader", "Clock", "Mail", "Radio", "Camera", "Keys", "Chat")
        return (0 until count).map { i ->
            val name = "${words[i % words.size]} ${kinds[(i / words.size) % kinds.size]} ${i + 1}"
            val status = when (i % 7) {
                0 -> AppStatus.UPDATE_AVAILABLE
                1 -> AppStatus.NOT_INSTALLED
                else -> AppStatus.UP_TO_DATE
            }
            val installed = if (status == AppStatus.NOT_INSTALLED) null else "1.${i % 10}"
            val offered = if (status == AppStatus.UPDATE_AVAILABLE) "1.${i % 10 + 1}" else "1.${i % 10}"
            row("many$i", name, status, installed, offered, categories = listOf(kinds[(i / words.size) % kinds.size]))
        }
    }

    fun events(): List<Event> {
        var id = 1000L
        fun e(hoursAgo: Double, appId: String?, name: String?, kind: EventKind, message: String) =
            Event(id--, now - (hoursAgo * HOUR).toLong(), appId, name, kind, message)
        return listOf(
            e(0.2, "trailmap", "Trail Map", EventKind.UPDATE_FOUND, "Version 1.5.0 is available."),
            e(0.5, "cinderplayer", "Cinder Player", EventKind.DOWNLOADED, "Downloaded cinderplayer-8.1-x86_64.apk (24 MB)."),
            e(1.0, "ferrybook", "Ferry Book", EventKind.BLOCKED, "Blocked: the new file is signed with a different certificate."),
            e(1.5, "pocketnotes", "Pocket Notes", EventKind.INSTALLED, "Installed version 2.3.1."),
            e(1.6, "pocketnotes", "Pocket Notes", EventKind.VERIFIED, "Signer matches the installed app. Checksum matched."),
            e(3.0, "ridgeradio", "Ridge Radio", EventKind.CHECK_FAILED, "GitHub is limiting requests from this network."),
            e(26.0, "kestrelmail", "Kestrel Mail", EventKind.ADDED, "Added from gitlab.com."),
            e(27.0, null, null, EventKind.IMPORTED, "Imported 14 apps from an Obtainium export."),
            e(30.0, "lanternpdf", "Lantern PDF", EventKind.FAILED, "The downloaded file does not match the checksum."),
            e(75.0, "oldapp", "Old App", EventKind.REMOVED, "Stopped following Old App."),
            e(100.0, "vaultkeys", "Vault Keys", EventKind.INSTALLED, "Installed version 12.0."),
        )
    }
}
