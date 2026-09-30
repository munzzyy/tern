package io.github.munzzyy.tern.core.net

/**
 * Sends every request to GitHub through a hubproxy instance, as Obtainium's GHReqPrefix does:
 * https://api.github.com/x goes to https://<proxy>/api.github.com/x, and the proxy fetches it.
 * The proxy sees each of these requests. It is never sent a token or a cookie, and the address of
 * an answer is handed back as GitHub's own, so nothing downstream can tell the difference.
 */
class GitHubProxyHttp(private val delegate: HttpClient, private val proxyHost: () -> String?) : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val proxy = proxyHost() ?: return delegate.execute(request)
        val through = GitHubProxy.through(request.url, proxy) ?: return delegate.execute(request)
        val headers = request.headers.filterKeys { name -> WITHHELD.none { it.equals(name, ignoreCase = true) } }
        val response = delegate.execute(request.copy(url = through, headers = headers, authorization = null))
        val url = GitHubProxy.unwrap(response.url)
        return if (url == response.url) response else HttpResponse(response.status, response.headers, response.body, url)
    }

    private companion object {
        val WITHHELD = listOf("Authorization", "Cookie", "Proxy-Authorization")
    }
}

object GitHubProxy {
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    /** GitHub's own hosts: the site, its API, and the hosts its files are served from. */
    fun isGitHubHost(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        return name == "github.com" || name.endsWith(".github.com") || name == "githubusercontent.com" || name.endsWith(".githubusercontent.com")
    }

    /** [url] as the proxy at [proxyHost] takes it, or null when [url] is not an https address on GitHub. */
    fun through(url: String, proxyHost: String): String? {
        val trimmed = url.trim()
        if (!Urls.isHttps(trimmed) || !isGitHubHost(Urls.host(trimmed))) return null
        return "https://$proxyHost/" + trimmed.substring(HTTPS.length)
    }

    /**
     * [url] without a proxy in front of it: https://proxy/github.com/x, and https://proxy/https://github.com/x
     * as some proxies write it, become https://github.com/x. Any other address stays as it is.
     */
    fun unwrap(url: String): String {
        val trimmed = url.trim()
        if (!Urls.isHttps(trimmed) || isGitHubHost(Urls.host(trimmed))) return url
        val inner = trimmed.substring(HTTPS.length).substringAfter('/', "").let { if (Urls.isHttps(it)) it.substring(HTTPS.length) else it }
        if (inner.isEmpty()) return url
        val unwrapped = HTTPS + inner
        return if (isGitHubHost(Urls.host(unwrapped))) unwrapped else url
    }

    /**
     * The host a person typed for the proxy, in lower case, or null when it is not a bare public host
     * name. An https:// in front and a slash behind are taken off; any other scheme, a port or a
     * path is refused, and so is GitHub itself.
     */
    fun cleanHost(text: String?): String? {
        var host = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (host.startsWith(HTTPS)) host = host.substring(HTTPS.length).removeSuffix("/")
        host = host.removeSuffix(".")
        if (host.length > 253 || '.' !in host || !host.split('.').all { LABEL.matches(it) }) return null
        if (isGitHubHost(host) || Urls.isLocal(host)) return null
        return host
    }

    private const val HTTPS = "https://"
}
