package io.github.munzzyy.tern.core.apk

import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.InsecureUrlException
import java.io.IOException

/**
 * A remote file read through explicit HTTP Range requests. Suffix ranges are never sent because
 * GitHub's release storage answers them with 501. The constructor asks for the first block, which
 * yields the size and ETag, then for the last [TAIL_BYTES], where the zip directory lives. Later
 * reads are rounded to [BLOCK_BYTES] and cached. Every Content-Range is checked against the request,
 * and the ETag (or Last-Modified) goes out as If-Range so a file swapped mid-way is refused.
 *
 * After the first answer, requests go straight to the URL the redirects ended at, without
 * [authorization], because that URL is already signed. If it has expired (401, 403, 404) the
 * original URL is tried once more.
 */
class HttpRangeSource(
    private val http: HttpClient,
    private val url: String,
    private val authorization: String? = null,
    private val budgetBytes: Long = DEFAULT_BUDGET,
) : RandomAccessSource {
    override val size: Long

    var requestCount: Int = 0
        private set
    var bytesFetched: Long = 0
        private set

    private val blocks = HashMap<Long, ByteArray>()
    private var target: String = url
    private var fellBack = false
    private var etag: String? = null
    private var ifRange: String? = null
    private val tailOffset: Long
    private val tail: ByteArray

    init {
        if (!url.startsWith("https://", ignoreCase = true)) throw InsecureUrlException(url)
        require(budgetBytes >= BLOCK_BYTES + TAIL_BYTES) { "budgetBytes must allow the first block and the tail" }
        val first = request(0, BLOCK_BYTES - 1, knownSize = null)
        size = first.total
        blocks[0] = first.body
        if (size > BLOCK_BYTES) {
            tailOffset = maxOf(BLOCK_BYTES, size - TAIL_BYTES)
            tail = request(tailOffset, size - 1, knownSize = size).body
        } else {
            tailOffset = size
            tail = ByteArray(0)
        }
    }

    override fun read(offset: Long, length: Int): ByteArray {
        checkRead(offset, length, size)
        val end = offset + length
        if (offset >= tailOffset) return tail.copyOfRange((offset - tailOffset).toInt(), (end - tailOffset).toInt())
        fetchMissing(offset / BLOCK_BYTES, (minOf(end, tailOffset) - 1) / BLOCK_BYTES)
        val out = ByteArray(length)
        var at = offset
        while (at < end) {
            if (at >= tailOffset) {
                tail.copyInto(out, (at - offset).toInt(), (at - tailOffset).toInt(), (end - tailOffset).toInt())
                break
            }
            val index = at / BLOCK_BYTES
            val block = blocks[index] ?: throw IOException("Block $index missing after fetch")
            val blockStart = index * BLOCK_BYTES
            val stop = minOf(end, blockStart + block.size)
            if (stop <= at) throw IOException("Block $index is shorter than expected")
            block.copyInto(out, (at - offset).toInt(), (at - blockStart).toInt(), (stop - blockStart).toInt())
            at = stop
        }
        return out
    }

    private fun fetchMissing(first: Long, last: Long) {
        var index = first
        while (index <= last) {
            if (blocks.containsKey(index)) {
                index++
                continue
            }
            var runEnd = index
            while (runEnd + 1 <= last && !blocks.containsKey(runEnd + 1)) runEnd++
            val start = index * BLOCK_BYTES
            val bytes = request(start, minOf((runEnd + 1) * BLOCK_BYTES, tailOffset) - 1, knownSize = size).body
            var at = 0
            var block = index
            while (at < bytes.size) {
                val n = minOf(BLOCK_BYTES.toInt(), bytes.size - at)
                blocks[block++] = bytes.copyOfRange(at, at + n)
                at += n
            }
            index = runEnd + 1
        }
    }

    private fun request(start: Long, end: Long, knownSize: Long?): Answer {
        if (bytesFetched + (end - start + 1) > budgetBytes) throw InspectionBudgetException(budgetBytes)
        var response = send(target, start, end)
        if (target != url && response.status in EXPIRED && !fellBack) {
            response.close()
            fellBack = true
            target = url
            response = send(url, start, end)
            if (response.status == 206) adoptFinalUrl(response.url)
        }
        return response.use { answer(it, start, end, knownSize) }
    }

    private fun send(to: String, start: Long, end: Long): HttpResponse {
        requestCount++
        val headers = buildMap {
            put("Range", "bytes=$start-$end")
            put("Accept-Encoding", "identity")
            ifRange?.let { put("If-Range", it) }
        }
        return http.execute(HttpRequest(to, headers = headers, authorization = authorization.takeIf { to == url }))
    }

    private fun answer(response: HttpResponse, start: Long, end: Long, knownSize: Long?): Answer {
        when (response.status) {
            206 -> {}
            200 -> throw if (ifRange != null) RemoteFileChangedException(url, "server sent the whole file again") else RangeNotSupportedException(url)
            416, 501 -> throw RangeNotSupportedException(url)
            else -> throw IOException("HTTP ${response.status} for a range request to $url")
        }
        val range = contentRange(response)
        if (knownSize != null && range.total != knownSize) throw RemoteFileChangedException(url, "size went from $knownSize to ${range.total}")
        val expectedEnd = minOf(end, range.total - 1)
        if (range.start != start || range.end != expectedEnd) {
            throw ApkFormatException("Asked for bytes $start-$end, got ${range.start}-${range.end}/${range.total}")
        }
        val seen = response.headers["ETag"]?.takeIf { it.isNotBlank() }
        if (knownSize == null) {
            etag = seen
            ifRange = seen?.takeUnless { it.startsWith("W/") } ?: response.headers["Last-Modified"]?.takeIf { it.isNotBlank() }
            adoptFinalUrl(response.url)
        } else if (etag != null && seen != null && seen != etag) {
            throw RemoteFileChangedException(url, "ETag went from $etag to $seen")
        }
        return Answer(range.total, body(response, range))
    }

    private fun adoptFinalUrl(finalUrl: String) {
        if (finalUrl.isNotBlank() && finalUrl != url && finalUrl.startsWith("https://", ignoreCase = true)) target = finalUrl
    }

    private fun contentRange(response: HttpResponse): ContentRange {
        val encoding = response.headers["Content-Encoding"]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            throw ApkFormatException("Range response is $encoding encoded")
        }
        val header = response.headers["Content-Range"] ?: throw ApkFormatException("Range response without Content-Range")
        val match = CONTENT_RANGE.matchEntire(header.trim()) ?: throw ApkFormatException("Malformed Content-Range: $header")
        val (start, end, total) = match.destructured.toList().map { it.toLongOrNull() ?: throw ApkFormatException("Malformed Content-Range: $header") }
        if (start > end || end >= total) throw ApkFormatException("Impossible Content-Range: $header")
        return ContentRange(start, end, total)
    }

    private fun body(response: HttpResponse, range: ContentRange): ByteArray {
        val expected = range.end - range.start + 1
        if (expected > Int.MAX_VALUE) throw ApkFormatException("Range too large")
        val bytes = response.bytes(expected.toInt())
        if (bytes.size.toLong() != expected) throw ApkFormatException("Range body is ${bytes.size} bytes, Content-Range promised $expected")
        bytesFetched += bytes.size
        return bytes
    }

    override fun close() {
        blocks.clear()
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private class Answer(val total: Long, val body: ByteArray)

    companion object {
        const val TAIL_BYTES = 65536L
        const val BLOCK_BYTES = 16384L
        const val DEFAULT_BUDGET = 4L * 1024 * 1024
        private val EXPIRED = setOf(401, 403, 404)
        private val CONTENT_RANGE = Regex("""bytes (\d{1,19})-(\d{1,19})/(\d{1,19})""")
    }
}
