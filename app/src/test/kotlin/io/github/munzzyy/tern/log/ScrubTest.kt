package io.github.munzzyy.tern.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing that could open an account, or name a person, goes into the log. Everything else stays as it was. */
class ScrubTest {
    private fun scrubbed(text: String) = Scrub.text(text)

    private fun unchanged(text: String) = assertEquals(text, scrubbed(text))

    // Addresses.

    @Test
    fun anAddressLosesItsQueryAndItsFragment() {
        assertEquals("https://example.org/a/b", scrubbed("https://example.org/a/b?token=abc&page=2#top"))
        assertEquals("https://example.org/a/b", scrubbed("https://example.org/a/b#access_token=abc"))
        assertEquals("http://example.org/", scrubbed("http://example.org/?a=b"))
        assertEquals("https://example.org", scrubbed("https://example.org?x"))
    }

    @Test
    fun aSignedAddressKeepsOnlyItsHostAndPath() {
        val signed = "HTTP 403 for https://objects.githubusercontent.com/github-production-release-asset/123/abc?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260930%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=9f86d081884c7d659a2feaa0c55ad015"
        assertEquals("HTTP 403 for https://objects.githubusercontent.com/github-production-release-asset/123/abc", scrubbed(signed))
    }

    @Test
    fun anAddressLosesItsNameAndPassword() {
        assertEquals("https://example.org/x", scrubbed("https://user:pass@example.org/x"))
        assertEquals("https://github.com/o/r.git", scrubbed("https://ghp_abc@github.com/o/r.git"))
        assertEquals("https://example.org/", scrubbed("https://me:p@ss@example.org/"))
        assertEquals("socks://127.0.0.1:9050", scrubbed("socks://tor:secret@127.0.0.1:9050"))
        assertEquals("ftp://example.org:21/file", scrubbed("ftp://anonymous@example.org:21/file"))
    }

    @Test
    fun anAtSignAfterTheHostIsNoPassword() {
        unchanged("https://example.org/@user/app")
        assertEquals("https://example.org/@user", scrubbed("https://example.org/@user?a=me@example.org"))
    }

    @Test
    fun anAddressInsideAnotherIsCleanedToo() {
        assertEquals("obtainium://add/https://example.org/app", scrubbed("obtainium://add/https://user:pass@example.org/app?key=1"))
        assertEquals(
            "obtainium://add/https%3A%2F%2Fgithub.com%2Fo%2Fr",
            scrubbed("obtainium://add/https%3A%2F%2Fme%3Asecret%40github.com%2Fo%2Fr"),
        )
        assertEquals("https://a.example/redirect", scrubbed("https://a.example/redirect?to=https://b.example/?token=x"))
    }

    @Test
    fun theSentenceAroundAnAddressIsKept() {
        assertEquals("Failed to fetch https://example.org/a.", scrubbed("Failed to fetch https://example.org/a?x=1."))
        assertEquals("(see https://example.org/a), then retry", scrubbed("(see https://example.org/a?x=1), then retry"))
        assertEquals("\"https://example.org/a\"", scrubbed("\"https://example.org/a?x=1\""))
        assertEquals(
            "https://a.example/1 and https://b.example/2",
            scrubbed("https://a.example/1?k=v and https://b.example/2#f"),
        )
    }

    @Test
    fun anAddressWithNothingToHideIsLeftAsItIs() {
        unchanged("https://github.com/example/app/releases/download/v1.2.3/app-arm64-v8a.apk")
        unchanged("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        unchanged("Range request to https://f-droid.org/repo/org.example.app_12.apk failed")
    }

    // Tokens.

    @Test
    fun githubAndGitlabTokensGoByTheirPrefix() {
        val tail = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
        for (prefix in listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) {
            assertEquals("The token … was refused", scrubbed("The token $prefix$tail was refused"))
        }
        assertEquals("…", scrubbed("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV"))
        assertEquals("PRIVATE-TOKEN: …", scrubbed("PRIVATE-TOKEN: glpat-xYz12_AbC-dEfGhIjKlMn"))
        assertEquals("deploy …", scrubbed("deploy gldt-abcdefghij0123456789"))
        assertEquals("sent …, got 401", scrubbed("sent ghp_$tail, got 401"))
    }

    @Test
    fun whatFollowsBearerGoes() {
        assertEquals("Authorization: Bearer …", scrubbed("Authorization: Bearer abc.def-ghi_jkl"))
        assertEquals("bearer …", scrubbed("bearer x"))
        assertEquals("sent Bearer … to api.github.com", scrubbed("sent Bearer 0123456789abcdef to api.github.com"))
    }

    @Test
    fun whatAnAuthorizationHeaderCarriesGoes() {
        assertEquals("Authorization: token …", scrubbed("Authorization: token 0123456789abcdef0123456789abcdef01234567"))
        assertEquals("Authorization: token …", scrubbed("Authorization: token short"))
        assertEquals("Authorization: Basic …", scrubbed("Authorization: Basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Proxy-Authorization: …", scrubbed("Proxy-Authorization: c2VjcmV0"))
        assertEquals("{\"Authorization\": \"Bearer …\"}", scrubbed("{\"Authorization\": \"Bearer abc\"}"))
    }

    @Test
    fun basicCredentialsGoAndTheWordBasicStays() {
        assertEquals("Basic …", scrubbed("Basic dXNlcjpwYXNz"))
        unchanged("Basic information about the app")
        unchanged("Basic auth failed")
    }

    @Test
    fun aJsonWebTokenGoes() {
        assertEquals("got … back", scrubbed("got eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl back"))
        assertEquals("Bearer …", scrubbed("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl"))
    }

    @Test
    fun aCodeAfterKeyTokenOrSecretGoes() {
        assertEquals("key=…", scrubbed("key=0123456789abcdef"))
        assertEquals("api_key: …", scrubbed("api_key: ABCDEF0123456789ABCDEF"))
        assertEquals("secret …", scrubbed("secret 9f86d081884c7d659a2feaa0c55ad015"))
        assertEquals("{\"token\":\"…\"}", scrubbed("{\"token\":\"dGhpcyBpcyBhIHNlY3JldCB0b2tlbg==\"}"))
        assertEquals("access_token=… and more", scrubbed("access_token=abc123def456 and more"))
        assertEquals("password: …", scrubbed("password: hunter2hunter2"))
        assertEquals("X-Auth-Token: …", scrubbed("X-Auth-Token: 2b7e151628aed2a6abf7158809cf4f3c"))
        assertEquals("client_secret = '…'", scrubbed("client_secret = 'Zm9vYmFyYmF6'"))
        assertEquals("credentials …", scrubbed("credentials 5f4dcc3b5aa765d61d8327deb882cf99"))
    }

    @Test
    fun aSessionOrACookieGoes() {
        assertEquals("Cookie: …", scrubbed("Cookie: sessionid=abc; csrftoken=Zx9; theme=dark"))
        assertEquals("Set-Cookie: …\nnext line", scrubbed("Set-Cookie: sid=a1; Path=/; HttpOnly\nnext line"))
        assertEquals("session_id=…", scrubbed("session_id=5f4dcc3b5aa765d6"))
        assertEquals("JSESSIONID: …", scrubbed("JSESSIONID: q8ne1bc7vd0d2k1p"))
        assertEquals("sid=… sent", scrubbed("sid=Zm9vYmFyYmF6 sent"))
        assertEquals("cookie=…", scrubbed("cookie=x1"))
    }

    @Test
    fun aShortPasswordGoesOnceItIsSet() {
        assertEquals("password: …", scrubbed("password: hunter2"))
        assertEquals("login failed, pwd=…", scrubbed("login failed, pwd=abc"))
        assertEquals("{\"passphrase\":\"…\"}", scrubbed("{\"passphrase\":\"tern\"}"))
        unchanged("Wrong password for user")
        unchanged("The password was not accepted")
        unchanged("Considered 12 apps")
    }

    @Test
    fun aCodeOfLettersAloneGoesWhenItIsLongOrMixed() {
        assertEquals("token …", scrubbed("token abcdefghijklmnopqrstuvwxyz"))
        assertEquals("key …", scrubbed("key AbCdEfGhIjKl"))
    }

    @Test
    fun everyCodeInATextGoesNotOnlyTheFirst() {
        assertEquals("token … and key …", scrubbed("token a1b2c3d4e5f6 and key f0e1d2c3b4a5"))
        assertEquals("Token token …", scrubbed("Token token 12345678"))
    }

    @Test
    fun wordsAfterKeyTokenOrSecretStay() {
        unchanged("The token for api.github.com was refused.")
        unchanged("A stored token for codeberg.org could not be opened (AEADBadTagException)")
        unchanged("The public key does not match the certificate")
        unchanged("Signed with key rotation")
        unchanged("key v1.2.3")
        unchanged("A secret handshake")
        unchanged("Session 1234 was already gone: null")
        unchanged("Install of org.example.app stopped: SocketTimeoutException: timeout")
        unchanged("Own check of the base: DoesNotHold(reason=the public key does not match the certificate)")
    }

    // People.

    @Test
    fun anEmailAddressGoes() {
        assertEquals("Reported by … today", scrubbed("Reported by someone.else+tern@mail.example.org today"))
        assertEquals("…:owner/repo", scrubbed("git@github.com:owner/repo"))
        unchanged("version app@1.2.3")
    }

    @Test
    fun theDevicesOwnAddressGoesAndTheServersStays() {
        assertEquals(
            "failed to connect to github.com/140.82.121.4 (port 443) from … (port 43210) after 10000ms",
            scrubbed("failed to connect to github.com/140.82.121.4 (port 443) from /192.168.1.23 (port 43210) after 10000ms"),
        )
        assertEquals("from … (port 43210)", scrubbed("from /2a02:8108:1234:5678::1 (port 43210)"))
        assertEquals("from … (port 5)", scrubbed("from phone.local/fe80::1%wlan0 (port 5)"))
        unchanged("Downloaded from github.com in 2 seconds")
        unchanged("failed to connect to /2a02:8108::1 (port 443) after 10000ms")
    }

    // As a whole.

    @Test
    fun whatIsCleanStaysClean() {
        val samples = listOf(
            "https://user:pass@example.org/a?token=b#c",
            "Authorization: Bearer abc and ghp_0123456789abcdef",
            "key=0123456789abcdef, mail me@example.org",
            "obtainium://add/https%3A%2F%2Fme%3Asecret%40github.com%2Fo%2Fr",
            "Cookie: sid=abc; pass=1",
            "pwd=pass1",
        )
        for (sample in samples) {
            val once = scrubbed(sample)
            assertEquals(sample, once, scrubbed(once))
            assertFalse(sample, once.contains("secret") || once.contains("pass") || once.contains("0123456789"))
        }
    }

    @Test
    fun theTextsOfTernsOwnWarningsComeThroughAsTheyWere() {
        unchanged("JobScheduler refused the periodic check")
        unchanged("Stored app 3f2a9b8c7d6e5f40 is unreadable: Expected a string at \$.source.url")
        unchanged("No install source for org.example.app: org.example.app")
        unchanged("OBB files of 3f2a9b8c7d6e5f40 were not put in place: dd exited with 1")
    }

    @Test(timeout = 5_000)
    fun longRunsTakeNoTimeAtAll() {
        val runs = listOf(
            "a".repeat(64_000),
            "https://" + "a".repeat(64_000),
            "token " + "a1".repeat(32_000),
            "x@".repeat(32_000),
            "%3A%2F%2F" + "a".repeat(64_000),
            "://" + "b".repeat(64_000),
            "key key key ".repeat(5_000),
            "eyJ" + "a".repeat(64_000),
        )
        for (run in runs) assertTrue(scrubbed(run).length <= run.length)
    }
}
