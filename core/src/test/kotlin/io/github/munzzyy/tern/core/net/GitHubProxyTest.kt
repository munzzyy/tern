package io.github.munzzyy.tern.core.net

import io.github.munzzyy.tern.core.testing.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubProxyTest {
    @Test
    fun everyRequestToGitHubGoesThroughTheProxyWithoutAToken() {
        val http = FakeHttp()
            .text("https://gh-proxy.example/api.github.com/repos/a/b/releases?per_page=30", "[]")
            .text("https://example.org/page", "ok")
        val proxied = GitHubProxyHttp(http) { "gh-proxy.example" }
        val answer = proxied.execute(HttpRequest("https://api.github.com/repos/a/b/releases?per_page=30", headers = mapOf("Cookie" to "x", "Accept" to "a"), authorization = "Bearer secret"))
        assertEquals("https://api.github.com/repos/a/b/releases?per_page=30", answer.url)
        val sent = http.requests.single()
        assertNull(sent.authorization)
        assertEquals(mapOf("Accept" to "a"), sent.headers)
        proxied.execute(HttpRequest("https://example.org/page", authorization = "Bearer other"))
        assertEquals("Bearer other", http.requestsTo("https://example.org/page").single().authorization)
        GitHubProxyHttp(http) { null }.execute(HttpRequest("https://example.org/page"))
        assertEquals(2, http.requestsTo("https://example.org/page").size)
    }

    @Test
    fun addressesComeBackWithoutTheProxyInFront() {
        assertEquals("https://github.com/a/b/releases/download/v1/app.apk", GitHubProxy.unwrap("https://gh-proxy.example/github.com/a/b/releases/download/v1/app.apk"))
        assertEquals("https://github.com/a/b", GitHubProxy.unwrap("https://gh-proxy.example/https://github.com/a/b"))
        assertEquals("https://example.org/files/app.apk", GitHubProxy.unwrap("https://example.org/files/app.apk"))
        assertEquals("https://github.com/a/b", GitHubProxy.unwrap("https://github.com/a/b"))
    }

    @Test
    fun onlyABarePublicHostIsTakenForTheProxy() {
        assertEquals("gh-proxy.com", GitHubProxy.cleanHost(" GH-Proxy.com "))
        assertEquals("gh-proxy.com", GitHubProxy.cleanHost("https://gh-proxy.com/"))
        for (bad in listOf("http://gh-proxy.com", "gh-proxy.com:8443", "gh-proxy.com/path", "localhost", "192.168.1.2", "github.com", "api.github.com", "nodot", "", null)) {
            assertNull(bad, GitHubProxy.cleanHost(bad))
        }
    }
}
