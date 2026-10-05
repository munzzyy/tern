package io.github.munzzyy.tern.install

import android.os.StatFs
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.verify.Fingerprints
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.Texts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Downloaded(val file: File, val sha256: String, val size: Long, val reused: Boolean)

/**
 * A download that failed on the way, as a network does now and then; it is worth another try,
 * after [waitMs] when the server asked for longer than the usual pause.
 */
class PassingFailure(val failure: StepFailure, val waitMs: Long = 0) : Exception(failure.message)

/**
 * Downloads into one folder per app. A partial file is resumed with Range plus If-Range, a finished
 * file is kept until [discard] so a failed install can be retried without downloading again.
 * A download that ends for want of space, or because the file is too large, leaves nothing behind.
 * One that fails on the way is tried again, three more times five seconds apart, each time from
 * where the server lets it go on; a server that asks for a wait of up to a minute gets it.
 * [sweep] clears out what nothing will ask for again.
 */
class Downloader(
    private val http: HttpClient,
    private val root: File,
    private val texts: Texts,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * The file [key] names, fetched from [url]. They differ for a source whose links expire: the
     * key stays the same from one attempt to the next, so a download cut short resumes from a
     * fresh link, and the file kept is found again. [headers] are what the source asks for.
     */
    suspend fun fetch(
        appId: String,
        key: String,
        url: String,
        authorization: String?,
        headers: Map<String, String> = emptyMap(),
        onProgress: (done: Long, total: Long?) -> Unit,
    ): Downloaded {
        hold(appId)
        try {
            return download(appId, key, url, authorization, headers, onProgress)
        } finally {
            letGo(appId)
        }
    }

    private suspend fun download(
        appId: String,
        key: String,
        url: String,
        authorization: String?,
        headers: Map<String, String>,
        onProgress: (done: Long, total: Long?) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        val files = FilesFor(folder(appId), key)
        reuse(files)?.let { return@withContext it }
        try {
            retrying(RETRIES, pause) {
                try {
                    attempt(files, key, url, authorization, headers, onProgress, allowResume = true)
                } catch (_: RestartFromZero) {
                    files.part.delete()
                    files.meta.delete()
                    attempt(files, key, url, authorization, headers, onProgress, allowResume = false)
                }
            }
        } catch (e: StepFailure) {
            if (e.kind == ProblemKind.STORAGE) {
                files.part.delete()
                files.meta.delete()
            }
            throw e
        }
    }

    fun folder(appId: String): File = File(root, folderName(appId))

    /** The finished file [key] names, if one is kept. */
    fun kept(appId: String, key: String): File? = FilesFor(folder(appId), key).done.takeIf { it.isFile }

    fun discard(appId: String, key: String) {
        val files = FilesFor(folder(appId), key)
        files.done.delete()
        files.part.delete()
        files.meta.delete()
    }

    fun discardAll(appId: String) {
        folder(appId).deleteRecursively()
    }

    /** Apps with a fetch under way, each with how many. */
    private val holds = HashMap<String, Int>()

    private fun hold(appId: String) = synchronized(holds) {
        holds[appId] = (holds[appId] ?: 0) + 1
    }

    private fun letGo(appId: String) = synchronized(holds) {
        val left = (holds[appId] ?: 1) - 1
        if (left > 0) holds[appId] = left else holds.remove(appId)
    }

    /**
     * Sweeps this downloader's folder with what [keep] gives, asked once no fetch can start. An app
     * a fetch is under way for is left alone, whatever [keep] says of it.
     */
    fun sweep(nowMs: Long, keep: () -> Map<String, Set<String>?>) = synchronized(holds) {
        sweep(root, keep() + holds.keys.associateWith<String, Set<String>?> { null }, nowMs)
    }

    private class RestartFromZero : Exception()

    private class FilesFor(val dir: File, key: String) {
        private val base = baseOf(key)
        val part = File(dir, "$base.part")
        val done = File(dir, "$base.bin")
        val meta = File(dir, "$base.meta")
    }

    private class Meta(val url: String, val validator: String?, val total: Long?, val sha256: String?)

    private fun readMeta(files: FilesFor): Meta? {
        if (!files.meta.isFile || files.meta.length() > 16 * 1024) return null
        val obj = runCatching { Json.parseObject(files.meta.readText()) }.getOrNull() ?: return null
        return Meta(obj.string("url") ?: return null, obj.string("validator"), obj.long("total"), obj.string("sha256"))
    }

    private fun writeMeta(files: FilesFor, meta: Meta) {
        val tmp = File(files.dir, files.meta.name + ".tmp")
        tmp.writeText(Json.write(Json.obj("url" to meta.url, "validator" to meta.validator, "total" to meta.total, "sha256" to meta.sha256)))
        if (!tmp.renameTo(files.meta)) throw IOException("Could not write download state")
    }

    private fun reuse(files: FilesFor): Downloaded? {
        if (!files.done.isFile) return null
        val meta = readMeta(files)
        val sha = meta?.sha256
        if (sha != null && meta.total == files.done.length()) {
            val digest = MessageDigest.getInstance("SHA-256")
            hashInto(digest, files.done)
            if (Fingerprints.toHex(digest.digest()) == sha) return Downloaded(files.done, sha, files.done.length(), reused = true)
        }
        files.done.delete()
        return null
    }

    private suspend fun attempt(
        files: FilesFor,
        key: String,
        url: String,
        authorization: String?,
        extraHeaders: Map<String, String>,
        onProgress: (Long, Long?) -> Unit,
        allowResume: Boolean,
    ): Downloaded {
        if (!files.dir.isDirectory && !files.dir.mkdirs()) throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
        val meta = readMeta(files)
        val resumeFrom = if (allowResume && meta != null && meta.url == key && meta.validator != null && files.part.isFile) files.part.length() else 0L
        if (resumeFrom == 0L) files.part.delete()

        val headers = buildMap {
            putAll(extraHeaders)
            put("Accept-Encoding", "identity")
            if (resumeFrom > 0) {
                put("Range", "bytes=$resumeFrom-")
                put("If-Range", meta!!.validator!!)
            }
        }
        val response = try {
            http.execute(HttpRequest(url, headers = headers, authorization = authorization))
        } catch (e: RateLimitedException) {
            val until = e.retryAtMs ?: throw PassingFailure(StepFailure(ProblemKind.NETWORK, texts.downloadFailed(e)))
            throw limited(until, nowMs(), texts.checkRateLimited(until))
        } catch (e: IOException) {
            throw PassingFailure(StepFailure(ProblemKind.NETWORK, texts.downloadFailed(e)))
        }
        return response.use { copy(it, files, key, resumeFrom, meta?.validator, onProgress) }
    }

    private suspend fun copy(
        response: HttpResponse,
        files: FilesFor,
        key: String,
        requestedFrom: Long,
        storedValidator: String?,
        onProgress: (Long, Long?) -> Unit,
    ): Downloaded {
        val start: Long
        val total: Long?
        when (response.status) {
            206 -> {
                if (requestedFrom == 0L) throw StepFailure(ProblemKind.NETWORK, texts.serverStatus(206))
                val range = CONTENT_RANGE.matchEntire(response.headers["Content-Range"].orEmpty().trim())
                    ?: throw RestartFromZero()
                val first = range.groupValues[1].toLong()
                if (first != requestedFrom) throw RestartFromZero()
                val etag = response.headers["ETag"]
                if (etag != null && storedValidator != null && storedValidator.startsWith("\"") && etag != storedValidator) throw RestartFromZero()
                start = requestedFrom
                total = range.groupValues[3].toLongOrNull()
            }
            200 -> {
                start = 0
                total = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }
            }
            416 -> throw RestartFromZero()
            401, 403 -> throw StepFailure(ProblemKind.AUTH, texts.serverStatus(response.status))
            404, 410 -> throw StepFailure(ProblemKind.NOT_FOUND, texts.serverStatus(response.status))
            else -> {
                val failure = StepFailure(ProblemKind.NETWORK, texts.serverStatus(response.status))
                throw if (passing(response.status)) PassingFailure(failure) else failure
            }
        }
        val encoding = response.headers["Content-Encoding"]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw StepFailure(ProblemKind.NETWORK, texts.encodedDownload(encoding))
        if (total != null && total > MAX_BYTES) throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
        if (total != null) {
            // What is already on disk from an earlier attempt has left the free space already.
            val needed = (total * SPACE_FACTOR).toLong() - start
            val free = freeBytes(files.dir)
            if (free < needed) throw StepFailure(ProblemKind.STORAGE, texts.noSpace(needed, free))
        }
        val validator = response.headers["ETag"]?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
            ?: response.headers["Last-Modified"]?.takeIf { it.isNotBlank() }
        writeMeta(files, Meta(key, validator, total, null))

        val digest = MessageDigest.getInstance("SHA-256")
        if (start > 0) hashInto(digest, files.part) else files.part.delete()

        val written = coroutineScope {
            val work = async(Dispatchers.IO) { stream(response, files.part, start, total, digest, onProgress) }
            try {
                work.await()
            } catch (e: CancellationException) {
                response.close()
                throw e
            }
        }
        if (total != null && written != total) throw PassingFailure(StepFailure(ProblemKind.NETWORK, texts.downloadCut(written, total)))
        val sha = Fingerprints.toHex(digest.digest())
        files.done.delete()
        if (!files.part.renameTo(files.done)) throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
        writeMeta(files, Meta(key, validator, written, sha))
        return Downloaded(files.done, sha, written, reused = false)
    }

    private suspend fun stream(response: HttpResponse, part: File, start: Long, total: Long?, digest: MessageDigest, onProgress: (Long, Long?) -> Unit): Long =
        coroutineScope {
            var done = start
            var lastReport = 0L
            var nextLook = start
            val buffer = ByteArray(64 * 1024)
            val out = try {
                FileOutputStream(part, start > 0)
            } catch (_: IOException) {
                throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
            }
            try {
                while (true) {
                    ensureActive()
                    val n = try {
                        response.body.read(buffer)
                    } catch (e: IOException) {
                        ensureActive()
                        throw PassingFailure(StepFailure(ProblemKind.NETWORK, texts.downloadFailed(e)))
                    }
                    if (n < 0) break
                    done += n
                    if (done > MAX_BYTES || (total != null && done > total)) throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
                    if (total == null && done >= nextLook) {
                        nextLook = done + SPACE_LOOK_BYTES
                        val needed = (done * (SPACE_FACTOR - 1)).toLong() + SPACE_RESERVE_BYTES
                        val free = freeBytes(part.parentFile ?: part)
                        if (free < needed) throw StepFailure(ProblemKind.STORAGE, texts.noSpace(needed, free))
                    }
                    try {
                        out.write(buffer, 0, n)
                    } catch (_: IOException) {
                        throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
                    }
                    digest.update(buffer, 0, n)
                    val now = nowMs()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        onProgress(done, total)
                    }
                }
                try {
                    out.fd.sync()
                    out.close()
                } catch (_: IOException) {
                    throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
                }
            } finally {
                runCatching { out.close() }
            }
            onProgress(done, total)
            done
        }

    private fun hashInto(digest: MessageDigest, file: File) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
    }

    companion object {
        /** What names one file of one release, whatever link it is fetched from at the moment. */
        fun key(releaseId: String, assetUrl: String): String = "$releaseId|$assetUrl"

        fun folderName(appId: String): String = Fingerprints.sha256(appId.toByteArray()).take(20)

        /** The name the files of [key] share before their ending. */
        fun baseOf(key: String): String = Fingerprints.sha256(key.toByteArray()).take(24)

        /** The keys of every file [state] can still ask for: those of each release it lists, and of the install it waits on. */
        fun keysOf(state: AppState): Set<String> = buildSet {
            for (release in state.releases) {
                for (asset in release.savable) for (url in listOf(asset.url) + asset.parts) add(key(release.id, url))
            }
            state.pending?.let { pending -> for (url in listOf(pending.assetUrl) + pending.partUrls) add(key(pending.releaseId, url)) }
        }

        /**
         * Deletes from [root] what nothing will ask for again: the folder of an app [keep] does not
         * name, the files of an app under no key [keep] gives it, and a download cut short that has
         * not moved for [ABANDONED_MS]. An app [keep] gives null is left as it is.
         */
        fun sweep(root: File, keep: Map<String, Set<String>?>, nowMs: Long) {
            val apps = keep.mapKeys { folderName(it.key) }
            for (folder in root.listFiles().orEmpty()) {
                if (folder.name !in apps) {
                    folder.deleteRecursively()
                    continue
                }
                val bases = apps[folder.name]?.mapTo(HashSet(), ::baseOf) ?: continue
                for ((base, files) in folder.listFiles().orEmpty().groupBy { it.name.substringBefore('.') }) {
                    val abandoned = files.none { it.name == "$base.bin" } && files.all { nowMs - it.lastModified() > ABANDONED_MS }
                    if (base !in bases || abandoned) files.forEach { it.deleteRecursively() }
                }
            }
        }

        const val ABANDONED_MS = 14L * 24 * 60 * 60 * 1000

        /** Tries after the first, as Obtainium makes them. */
        const val RETRIES = 3
        const val RETRY_WAIT_MS = 5_000L

        /** An answer that says the server is busy or struggling now, not that the file cannot be had. */
        fun passing(status: Int): Boolean = status == 408 || status == 429 || status in 500..599

        /**
         * Runs [block], and again each time it fails in passing, [tries] more times at the most. In
         * between it [wait]s [RETRY_WAIT_MS], or as long as the failure asks when that is longer.
         * The last failure is the one given.
         */
        suspend fun <T> retrying(tries: Int, wait: suspend (Long) -> Unit, block: suspend () -> T): T {
            var left = tries
            while (true) {
                try {
                    return block()
                } catch (e: PassingFailure) {
                    if (left-- <= 0) throw e.failure
                    wait(maxOf(RETRY_WAIT_MS, e.waitMs))
                }
            }
        }

        /**
         * A server that asked for a wait until [retryAtMs]: a wait of up to [MAX_RATE_WAIT_MS] is
         * sat out and the download tried again, and a longer one ends it, saying [message].
         */
        fun limited(retryAtMs: Long, nowMs: Long, message: String): Exception {
            val failure = StepFailure(ProblemKind.RATE_LIMITED, message, retryAtMs)
            val waitMs = (retryAtMs - nowMs).coerceAtLeast(0)
            return if (waitMs <= MAX_RATE_WAIT_MS) PassingFailure(failure, waitMs) else failure
        }

        /** The longest a download waits for a server, holding the one download at a time some settings allow. */
        const val MAX_RATE_WAIT_MS = 60_000L

        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        const val SPACE_FACTOR = 2.2

        /** A server that names no length is watched while it sends: this often, and this much is left free. */
        const val SPACE_LOOK_BYTES = 4L * 1024 * 1024
        const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 100L
        private val CONTENT_RANGE = Regex("""bytes (\d{1,19})-(\d{1,19})/(\d{1,19}|\*)""")
    }
}
