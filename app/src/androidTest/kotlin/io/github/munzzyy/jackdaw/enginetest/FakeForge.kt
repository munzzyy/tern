package io.github.munzzyy.jackdaw.enginetest

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonArray
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import java.util.concurrent.CopyOnWriteArrayList

/** A Forgejo instance at https://forge.test serving example/app from memory, with real Range semantics. */
class FakeForge : HttpClient {
    class File(val name: String, val bytes: ByteArray) {
        val etag = "\"${Fingerprints.sha256(bytes).take(16)}\""
    }

    class Release(val tag: String, val files: List<File>, val prerelease: Boolean = false)

    val requests = CopyOnWriteArrayList<HttpRequest>()

    @Volatile
    var releases: List<Release> = emptyList()

    val fileRequests: List<HttpRequest> get() = requests.filter { it.url.startsWith(FILES) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val url = request.url
        return when {
            url == "$BASE/api/v1/version" -> json("""{"version":"1.21.0"}""", url)
            url.startsWith("$BASE/api/v1/repos/example/app/releases") -> listing(request)
            url.startsWith(FILES) -> file(request)
            else -> HttpResponse.of(404, "not here", url = url)
        }
    }

    private fun listing(request: HttpRequest): HttpResponse {
        val body = Json.write(
            JsonArray(
                releases.map { r ->
                    Json.obj(
                        "tag_name" to r.tag,
                        "name" to "Version ${r.tag}",
                        "body" to "Notes for ${r.tag}",
                        "draft" to false,
                        "prerelease" to r.prerelease,
                        "published_at" to "2026-01-01T00:00:00Z",
                        "html_url" to "$BASE/example/app/releases/tag/${r.tag}",
                        "assets" to r.files.map { f ->
                            Json.obj("name" to f.name, "browser_download_url" to "$FILES${r.tag}/${f.name}", "size" to f.bytes.size)
                        },
                    )
                },
            ),
        )
        val etag = "\"${Fingerprints.sha256(body.toByteArray()).take(16)}\""
        if (request.headers["If-None-Match"] == etag) return HttpResponse.of(304, "", Headers.of("ETag" to etag), request.url)
        return json(body, request.url, "ETag" to etag)
    }

    private fun file(request: HttpRequest): HttpResponse {
        val path = request.url.removePrefix(FILES)
        val tag = path.substringBefore('/')
        val name = path.substringAfter('/')
        val file = releases.firstOrNull { it.tag == tag }?.files?.firstOrNull { it.name == name }
            ?: return HttpResponse.of(404, "no such file", url = request.url)
        val bytes = file.bytes
        val range = request.headers["Range"]
        val ifRange = request.headers["If-Range"]
        val base = listOf("ETag" to file.etag, "Accept-Ranges" to "bytes")
        if (request.method == "HEAD") return HttpResponse.of(200, ByteArray(0), Headers.of(base + ("Content-Length" to bytes.size.toString())), request.url)
        val match = range?.let { RANGE.matchEntire(it) }
        if (match == null || (ifRange != null && ifRange != file.etag)) {
            return HttpResponse.of(200, bytes, Headers.of(base + ("Content-Length" to bytes.size.toString())), request.url)
        }
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(bytes.size - 1L) ?: (bytes.size - 1L)
        if (start >= bytes.size || start > end) return HttpResponse.of(416, "", Headers.of("Content-Range" to "bytes */${bytes.size}"), request.url)
        val slice = bytes.copyOfRange(start.toInt(), end.toInt() + 1)
        return HttpResponse.of(
            206, slice,
            Headers.of(base + listOf("Content-Range" to "bytes $start-$end/${bytes.size}", "Content-Length" to slice.size.toString())),
            request.url,
        )
    }

    private fun json(body: String, url: String, vararg extra: Pair<String, String>) =
        HttpResponse.of(200, body, Headers.of(listOf("Content-Type" to "application/json") + extra), url)

    companion object {
        const val BASE = "https://forge.test"
        const val FILES = "$BASE/files/"
        const val PROJECT = "$BASE/example/app"
        private val RANGE = Regex("""bytes=(\d+)-(\d*)""")
    }
}
