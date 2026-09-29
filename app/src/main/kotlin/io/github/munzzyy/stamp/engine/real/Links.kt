package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.net.HttpClient
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.InsecureUrlException
import io.github.munzzyy.stamp.core.net.RateLimitedException
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemException
import io.github.munzzyy.stamp.engine.ProblemKind
import java.io.IOException

/**
 * Fetches an export from an address a person typed. The request carries no token, whatever is
 * stored for that host, because the address can come from anyone.
 */
internal class Links(
    private val http: HttpClient,
    private val texts: ImportTexts,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun read(input: String): Decoded = ImportDecoder.decode(fetch(input), texts, texts.linkNotAnExport())

    fun fetch(input: String): ByteArray {
        val request = HttpRequest(address(input), headers = mapOf("Accept" to ACCEPT))
        val response = try {
            http.execute(request)
        } catch (_: InsecureUrlException) {
            throw problem(ProblemKind.UNSUPPORTED, texts.linkLeavesHttps())
        } catch (ex: RateLimitedException) {
            throw ProblemException(Problem(ProblemKind.RATE_LIMITED, texts.checkRateLimited(ex.retryAtMs), ex.retryAtMs))
        } catch (ex: IOException) {
            throw problem(ProblemKind.NETWORK, texts.linkUnreachable(ex.message))
        }
        return response.use { answer ->
            // The client follows redirects by itself, so where the answer came from is checked once more here.
            if (!Urls.isHttps(answer.url)) throw problem(ProblemKind.UNSUPPORTED, texts.linkLeavesHttps())
            when {
                answer.status == 404 || answer.status == 410 -> throw problem(ProblemKind.NOT_FOUND, texts.linkNotFound())
                answer.status == 401 || answer.status == 403 -> throw problem(ProblemKind.AUTH, texts.linkRefused(answer.status))
                !answer.isSuccess -> throw problem(ProblemKind.NETWORK, texts.serverStatus(answer.status))
            }
            val announced = answer.headers["Content-Length"]?.trim()?.toLongOrNull()
            if (announced != null && announced > MAX_BYTES) throw tooLarge()
            try {
                Capped.read(answer.body, MAX_BYTES, nowMs() + BODY_TIMEOUT_MS, nowMs)
            } catch (_: Capped.TooLarge) {
                throw tooLarge()
            } catch (_: Capped.TooSlow) {
                throw problem(ProblemKind.NETWORK, texts.linkTooSlow())
            } catch (ex: IOException) {
                throw problem(ProblemKind.NETWORK, texts.linkUnreachable(ex.message))
            }
        }
    }

    private fun address(input: String): String {
        val typed = input.trim()
        if (typed.length > MAX_ADDRESS) throw problem(ProblemKind.UNSUPPORTED, texts.linkNotAnAddress())
        val written = when {
            Urls.isHttps(typed) -> typed
            typed.contains("://") -> throw problem(ProblemKind.UNSUPPORTED, texts.linkNotHttps())
            else -> "https://$typed"
        }
        return Urls.normalize(written) ?: throw problem(ProblemKind.UNSUPPORTED, texts.linkNotAnAddress())
    }

    private fun tooLarge() = problem(ProblemKind.UNSUPPORTED, texts.importTooLarge(MAX_BYTES))

    private fun problem(kind: ProblemKind, message: String) = ProblemException(Problem(kind, message))

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val BODY_TIMEOUT_MS = 60_000L
        private const val MAX_ADDRESS = 2048
        private const val ACCEPT = "application/json, text/plain;q=0.9, */*;q=0.5"
    }
}
