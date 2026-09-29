package io.github.munzzyy.stamp.engine.real

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.munzzyy.stamp.core.icon.IconAddresses
import io.github.munzzyy.stamp.core.icon.ImageProbe
import io.github.munzzyy.stamp.core.net.HttpClient
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.InsecureUrlException
import io.github.munzzyy.stamp.core.verify.Fingerprints
import io.github.munzzyy.stamp.engine.AppRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures fetched from where an app is published, kept in [dir] under the hash of their address.
 * What a server sends is judged by its own bytes and never reaches a decoder unless it is one
 * whole PNG, JPEG or WebP of a sane size. No request carries a token.
 */
internal class Icons(
    private val http: HttpClient,
    private val dir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val locks = Array(STRIPES) { Mutex() }
    private val fetching = Semaphore(MAX_PARALLEL)

    /**
     * The first of [addresses] that gives a picture [decode] accepts. Without [mayFetch] only what
     * is already kept is looked at, however old.
     */
    suspend fun <T : Any> load(addresses: List<String>, mayFetch: Boolean, decode: (ByteArray) -> T?): T? {
        for (address in addresses.take(IconAddresses.MAX_ADDRESSES)) {
            val key = Fingerprints.sha256(address.toByteArray())
            val lock = locks[key.take(2).toInt(16) % STRIPES]
            lock.withLock { one(address, key, mayFetch, decode) }?.let { return it }
        }
        return null
    }

    private suspend fun <T : Any> one(address: String, key: String, mayFetch: Boolean, decode: (ByteArray) -> T?): T? {
        val file = File(dir, "$key$PICTURE")
        val refusal = File(dir, "$key$REFUSAL")
        val kept = kept(file)
        val fresh = kept != null && nowMs() - file.lastModified() in 0..REFRESH_MS
        if (fresh || !mayFetch || refused(refusal)) return kept?.let { shown(it, file, refusal, decode) }

        return when (val got = fetching.withPermit { runInterruptible(Dispatchers.IO) { fetch(address) } }) {
            is Fetched.Picture -> {
                keep(file, got.bytes)
                refusal.delete()
                trim()
                shown(got.bytes, file, refusal, decode)
            }
            is Fetched.Failed -> {
                refuse(refusal, got.forMs)
                kept?.let { shown(it, file, refusal, decode) }
            }
        }
    }

    private fun <T : Any> shown(bytes: ByteArray, file: File, refusal: File, decode: (ByteArray) -> T?): T? {
        decode(bytes)?.let { return it }
        file.delete()
        refuse(refusal, DAY_MS)
        return null
    }

    private sealed interface Fetched {
        class Picture(val bytes: ByteArray) : Fetched

        class Failed(val forMs: Long) : Fetched
    }

    private fun fetch(address: String): Fetched {
        val deadline = nowMs() + FETCH_MS
        return try {
            http.execute(HttpRequest(address, headers = HEADERS)).use { response ->
                val declared = response.headers["Content-Length"]?.trim()?.toLongOrNull()
                when {
                    response.status in ASK_AGAIN_SOON -> Fetched.Failed(SOON_MS)
                    response.status != 200 -> Fetched.Failed(DAY_MS)
                    declared != null && declared > MAX_BYTES -> Fetched.Failed(DAY_MS)
                    else -> body(response.body, deadline)?.takeIf(::usable)?.let(Fetched::Picture) ?: Fetched.Failed(DAY_MS)
                }
            }
        } catch (_: InsecureUrlException) {
            Fetched.Failed(DAY_MS)
        } catch (_: IOException) {
            Fetched.Failed(SOON_MS)
        } catch (_: RuntimeException) {
            Fetched.Failed(DAY_MS)
        }
    }

    /** Null when the body is larger than an icon may be. */
    private fun body(input: InputStream, deadline: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
            if (nowMs() > deadline) throw IOException("The icon took too long to arrive")
        }
    }

    private fun usable(bytes: ByteArray): Boolean {
        val facts = ImageProbe.read(bytes) ?: return false
        return facts.width <= MAX_SIDE && facts.height <= MAX_SIDE
    }

    private fun kept(file: File): ByteArray? {
        if (!file.isFile) return null
        val bytes = try {
            if (file.length() > MAX_BYTES) null else file.readBytes()
        } catch (_: IOException) {
            null
        }
        if (bytes != null && usable(bytes)) return bytes
        file.delete()
        return null
    }

    private fun keep(file: File, bytes: ByteArray) {
        val part = File(dir, file.name + PART)
        try {
            dir.mkdirs()
            part.writeBytes(bytes)
            if (part.renameTo(file)) file.setLastModified(nowMs())
        } catch (_: IOException) {
        } finally {
            part.delete()
        }
    }

    private fun refused(refusal: File): Boolean {
        if (!refusal.isFile) return false
        val until = try {
            if (refusal.length() > 32) null else refusal.readText().trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
        if (until != null && until - nowMs() in 1..DAY_MS) return true
        refusal.delete()
        return false
    }

    private fun refuse(refusal: File, forMs: Long) {
        try {
            dir.mkdirs()
            refusal.writeText((nowMs() + forMs).toString())
            refusal.setLastModified(nowMs())
        } catch (_: IOException) {
        }
    }

    @Synchronized
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= MAX_TOTAL_BYTES && count <= MAX_FILES) return
            bytes -= file.length()
            count--
            file.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_SIDE = 2048
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        const val MAX_FILES = 2048
        const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** How long an address that gave no answer at all is left alone, where one that gave a wrong answer is left alone for a day. */
        const val SOON_MS = 15L * 60 * 1000
        const val FETCH_MS = 20_000L
        const val PICTURE = ".img"
        const val REFUSAL = ".no"
        private const val PART = ".part"
        private const val STRIPES = 16
        private const val MAX_PARALLEL = 4
        private val ASK_AGAIN_SOON = setOf(408, 425, 429, 500, 502, 503, 504)
        private val HEADERS = mapOf("Accept" to "image/png, image/jpeg, image/webp", "Accept-Encoding" to "identity")

        /** The largest power of two that still leaves [longest] at [wanted] or more once divided by it. */
        fun sampleFor(longest: Int, wanted: Int): Int {
            var sample = 1
            while (sample < 64 && wanted > 0 && longest / (sample * 2) >= wanted) sample *= 2
            return sample
        }
    }
}

internal object IconBitmaps {
    private val KINDS = setOf("image/png", "image/jpeg", "image/webp")

    /** The picture no larger than [sizePx] on its longer side, or null when Android cannot or must not decode it. */
    fun decode(bytes: ByteArray, sizePx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (min(bounds.outWidth, bounds.outHeight) < 1 || longest > Icons.MAX_SIDE || bounds.outMimeType !in KINDS) {
            null
        } else {
            val scaled = BitmapFactory.Options().apply { inSampleSize = Icons.sampleFor(longest, sizePx) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaled)?.let { fit(it, sizePx) }
        }
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun fit(bitmap: Bitmap, sizePx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= sizePx) return bitmap
        val width = max(1, bitmap.width * sizePx / longest)
        val height = max(1, bitmap.height * sizePx / longest)
        val fitted = bitmap.scale(width, height)
        if (fitted !== bitmap) bitmap.recycle()
        return fitted
    }
}

/** The icon of a row from its source, for a row that has neither an installed app nor a downloaded file to take it from. */
internal class SourceIcons(private val e: RealEngine) {
    private val icons = Icons(e.http, File(e.context.cacheDir, FOLDER), e.nowMs)

    suspend fun forRow(row: AppRow, sizePx: Int): Bitmap? {
        if (!e.settings.value.sourceIcons) return null
        val online = e.online.value
        return icons.load(addresses(row.id, wait = online), mayFetch = online) { IconBitmaps.decode(it, sizePx) }
    }

    // A row is drawn before its first check has named the addresses, and the screen asks once.
    private suspend fun addresses(id: String, wait: Boolean): List<String> {
        val known = { e.stored[id]?.state?.iconUrls.orEmpty() }
        if (!wait || known().isNotEmpty()) return known()
        withTimeoutOrNull(FIRST_CHECK_MS) {
            e.apps.first { rows -> rows.firstOrNull { it.id == id }?.let { !it.checking && it.lastCheckedMs != null } ?: true }
        }
        return known()
    }

    private companion object {
        const val FOLDER = "icons"
        const val FIRST_CHECK_MS = 20_000L
    }
}
