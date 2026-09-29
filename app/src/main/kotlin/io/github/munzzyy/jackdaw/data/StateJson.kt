package io.github.munzzyy.jackdaw.data

import io.github.munzzyy.jackdaw.core.engine.InstallRecord
import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonArray
import io.github.munzzyy.jackdaw.core.json.JsonObject
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.NotesFormat
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind

/** The engine's own storage format. It reads what it wrote; anything unreadable becomes an empty state, never a crash. */
object StateJson {
    const val MAX_RELEASES = 30
    const val MAX_NOTES = 20_000

    fun encode(state: AppState): String = Json.write(
        Json.obj(
            "releases" to JsonArray(state.releases.take(MAX_RELEASES).map(::release)),
            "lastCheckedMs" to state.lastCheckedMs,
            "checkProblem" to state.checkProblem?.let(::problem),
            "record" to state.record?.let(::record),
            "pending" to state.pending?.let(::pending),
            "block" to state.block?.let { Json.obj("releaseId" to it.releaseId, "assetUrl" to it.assetUrl, "problem" to problem(it.problem)) },
            "installProblem" to state.installProblem?.let(::problem),
            "seenReleaseId" to state.seenReleaseId,
            "movedTo" to state.movedTo,
            "patternProblem" to state.patternProblem?.let { Json.obj("filters" to it.filters, "message" to it.message) },
            "description" to state.description,
            "announcedReleaseId" to state.announcedReleaseId,
        ),
    )

    fun decode(text: String?): AppState {
        if (text.isNullOrEmpty()) return AppState()
        val obj = runCatching { Json.parseObject(text) }.getOrNull() ?: return AppState()
        return AppState(
            releases = obj.array("releases")?.objects().orEmpty().mapNotNull(::release).take(MAX_RELEASES),
            lastCheckedMs = obj.long("lastCheckedMs"),
            checkProblem = obj.obj("checkProblem")?.let(::problem),
            record = obj.obj("record")?.let(::record),
            pending = obj.obj("pending")?.let(::pending),
            block = obj.obj("block")?.let { b ->
                val releaseId = b.string("releaseId") ?: return@let null
                val assetUrl = b.string("assetUrl") ?: return@let null
                val problem = b.obj("problem")?.let(::problem) ?: return@let null
                GateBlock(releaseId, assetUrl, problem)
            },
            installProblem = obj.obj("installProblem")?.let(::problem),
            seenReleaseId = obj.string("seenReleaseId"),
            movedTo = obj.string("movedTo"),
            patternProblem = obj.obj("patternProblem")?.let { p ->
                PatternProblem(p.string("filters") ?: return@let null, p.string("message") ?: return@let null)
            },
            description = obj.string("description"),
            announcedReleaseId = obj.string("announcedReleaseId"),
        )
    }

    fun encodeFacts(facts: FileFacts): String = Json.write(
        Json.obj(
            "packageName" to facts.packageName,
            "versionCode" to facts.versionCode,
            "versionName" to facts.versionName,
            "signers" to facts.signers,
            "lineage" to facts.lineage,
            "permissions" to facts.permissions,
            "minSdk" to facts.minSdk,
            "targetSdk" to facts.targetSdk,
            "testOnly" to facts.testOnly,
            "verified" to facts.verified,
            "checksumMatchedFrom" to facts.checksumMatchedFrom,
        ),
    )

    fun decodeFacts(text: String?): FileFacts? {
        val obj = text?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: return null
        return FileFacts(
            packageName = obj.string("packageName") ?: return null,
            versionCode = obj.long("versionCode") ?: return null,
            versionName = obj.string("versionName"),
            signers = obj.array("signers")?.strings().orEmpty(),
            lineage = obj.array("lineage")?.strings().orEmpty(),
            permissions = obj.array("permissions")?.strings().orEmpty(),
            minSdk = obj.long("minSdk")?.toInt(),
            targetSdk = obj.long("targetSdk")?.toInt(),
            testOnly = obj.bool("testOnly") ?: false,
            verified = obj.bool("verified") ?: false,
            checksumMatchedFrom = obj.string("checksumMatchedFrom"),
        )
    }

    private fun release(r: Release): JsonObject = Json.obj(
        "id" to r.id,
        "version" to r.version,
        "versionCode" to r.versionCode,
        "title" to r.title,
        "notes" to r.notes?.take(MAX_NOTES),
        "notesFormat" to r.notesFormat.name,
        "publishedAtMs" to r.publishedAtMs,
        "prerelease" to r.prerelease,
        "pageUrl" to r.pageUrl,
        "assets" to JsonArray(
            r.assets.map { a ->
                Json.obj(
                    "name" to a.name, "url" to a.url, "size" to a.size, "sha256" to a.sha256,
                    "kind" to a.kind.name, "needsAuth" to a.needsAuth, "signers" to a.signers,
                )
            },
        ),
    )

    private fun release(obj: JsonObject): Release? = Release(
        id = obj.string("id") ?: return null,
        version = obj.string("version") ?: "",
        versionCode = obj.long("versionCode"),
        title = obj.string("title"),
        notes = obj.string("notes"),
        notesFormat = enumOr(obj.string("notesFormat"), NotesFormat.MARKDOWN),
        publishedAtMs = obj.long("publishedAtMs"),
        prerelease = obj.bool("prerelease") ?: false,
        pageUrl = obj.string("pageUrl"),
        assets = obj.array("assets")?.objects().orEmpty().mapNotNull { a ->
            val name = a.string("name") ?: return@mapNotNull null
            Asset(
                name = name,
                url = a.string("url") ?: return@mapNotNull null,
                size = a.long("size"),
                sha256 = a.string("sha256"),
                kind = enumOr(a.string("kind"), Asset.kindOf(name)),
                needsAuth = a.bool("needsAuth") ?: false,
                signers = a.array("signers")?.strings().orEmpty(),
            )
        },
    )

    private fun problem(p: Problem): JsonObject = Json.obj("kind" to p.kind.name, "message" to p.message, "retryAtMs" to p.retryAtMs)

    private fun problem(obj: JsonObject): Problem? {
        val kind = obj.string("kind")?.let { k -> ProblemKind.entries.firstOrNull { it.name == k } } ?: return null
        return Problem(kind, obj.string("message").orEmpty(), obj.long("retryAtMs"))
    }

    private fun record(r: InstallRecord): JsonObject = Json.obj(
        "releaseId" to r.releaseId, "version" to r.version, "versionCode" to r.versionCode,
        "fileSha256" to r.fileSha256, "fileSize" to r.fileSize,
    )

    private fun record(obj: JsonObject): InstallRecord? = InstallRecord(
        releaseId = obj.string("releaseId") ?: return null,
        version = obj.string("version") ?: "",
        versionCode = obj.long("versionCode") ?: return null,
        fileSha256 = obj.string("fileSha256"),
        fileSize = obj.long("fileSize"),
    )

    private fun pending(p: PendingInstall): JsonObject = Json.obj(
        "sessionId" to p.sessionId, "packageName" to p.packageName, "releaseId" to p.releaseId, "version" to p.version, "versionCode" to p.versionCode,
        "fileSha256" to p.fileSha256, "fileSize" to p.fileSize, "assetUrl" to p.assetUrl,
        "startedAtMs" to p.startedAtMs, "waitingForUser" to p.waitingForUser,
    )

    private fun pending(obj: JsonObject): PendingInstall? = PendingInstall(
        sessionId = obj.long("sessionId")?.toInt() ?: return null,
        packageName = obj.string("packageName") ?: return null,
        releaseId = obj.string("releaseId") ?: return null,
        version = obj.string("version") ?: "",
        versionCode = obj.long("versionCode") ?: return null,
        fileSha256 = obj.string("fileSha256"),
        fileSize = obj.long("fileSize"),
        assetUrl = obj.string("assetUrl") ?: "",
        startedAtMs = obj.long("startedAtMs") ?: 0L,
        waitingForUser = obj.bool("waitingForUser") ?: false,
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
