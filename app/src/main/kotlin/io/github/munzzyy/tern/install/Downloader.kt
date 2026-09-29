package io.github.munzzyy.tern.install

import android.os.StatFs
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.verify.Fingerprints
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Downloaded(val file: File, val sha256: String, val size: Long, val reused: Boolean)

/**
 * Downloads into one folder per app. A partial file is resumed with Range plus If-Range, a finished
 * file is kept until [discard] so a failed install can be retried without downloading again.
 * A download that ends for want of space, or because the file is too large, leaves nothing behind.
 */
class Downloader(
    private val http: HttpClient,
    private val root: File,
    private val texts: Texts,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetch(
        appId: String,
        url: String,
        authorization: String?,
        onProgress: (done: Long, total: Long?) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        val files = FilesFor(folder(appId), url)
        reuse(files)?.let { return@withContext it }
        try {
            try {
                attempt(files, url, authorization, onProgress, allowResume = true)
            } catch (_: RestartFromZero) {
                files.part.delete()
                files.meta.delete()
                attempt(files, url, authorization, onProgress, allowResume = false)
            }
        } catch (e: StepFailure) {
            if (e.kind == ProblemKind.STORAGE) {
                files.part.delete()
                files.meta.delete()
            }
            throw e
        }
    }

    fun folder(appId: String): File = File(root, Fingerprints.sha256(appId.toByteArray()).take(20))

    /** The finished file for [url], if one is kept. */
    fun kept(appId: String, url: String): File? = FilesFor(folder(appId), url).done.takeIf { it.isFile }

    fun discard(appId: String, url: String) {
        val files = FilesFor(folder(appId), url)
        files.done.delete()
        files.part.delete()
        files.meta.delete()
    }

    fun discardAll(appId: String) {
        folder(appId).deleteRecursively()
    }

    private class RestartFromZero : Exception()

    private class FilesFor(val dir: File, url: String) {
        private val base = Fingerprints.sha256(url.toByteArray()).take(24)
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
        url: String,
        authorization: String?,
        onProgress: (Long, Long?) -> Unit,
        allowResume: Boolean,
    ): Downloaded {
        if (!files.dir.isDirectory && !files.dir.mkdirs()) throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
        val meta = readMeta(files)
        val resumeFrom = if (allowResume && meta != null && meta.url == url && meta.validator != null && files.part.isFile) files.part.length() else 0L
        if (resumeFrom == 0L) files.part.delete()

        val headers = buildMap {
            put("Accept-Encoding", "identity")
            if (resumeFrom > 0) {
                put("Range", "bytes=$resumeFrom-")
                put("If-Range", meta!!.validator!!)
            }
        }
        val response = try {
            http.execute(HttpRequest(url, headers = headers, authorization = authorization))
        } catch (e: IOException) {
            throw StepFailure(ProblemKind.NETWORK, texts.downloadFailed(e))
        }
        return response.use { copy(it, files, url, resumeFrom, meta?.validator, onProgress) }
    }

    private suspend fun copy(
        response: HttpResponse,
        files: FilesFor,
        url: String,
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
            else -> throw StepFailure(ProblemKind.NETWORK, texts.serverStatus(response.status))
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
        writeMeta(files, Meta(url, validator, total, null))

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
        if (total != null && written != total) throw StepFailure(ProblemKind.NETWORK, texts.downloadCut(written, total))
        val sha = Fingerprints.toHex(digest.digest())
        files.done.delete()
        if (!files.part.renameTo(files.done)) throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
        writeMeta(files, Meta(url, validator, written, sha))
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
                        throw StepFailure(ProblemKind.NETWORK, texts.downloadFailed(e))
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
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        const val SPACE_FACTOR = 2.2

        /** A server that names no length is watched while it sends: this often, and this much is left free. */
        const val SPACE_LOOK_BYTES = 4L * 1024 * 1024
        const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024
        const val PROGRESS_INTERVAL_MS = 100L
        private val CONTENT_RANGE = Regex("""bytes (\d{1,19})-(\d{1,19})/(\d{1,19}|\*)""")
    }
}
