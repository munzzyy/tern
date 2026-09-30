package io.github.munzzyy.tern.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tern asks every source as itself: with the User-Agent PoliteHttp gives every request, never
 * another app's, and never with a key or a signature lifted from another app. The sources are read
 * as text, so that a new one that borrows another client's name fails here before anyone reviews it.
 */
class HonestIdentityTest {
    private val sources = File("src/main/kotlin/io/github/munzzyy/tern/core/source")

    private val forbidden = listOf(
        Regex("\"User-Agent\"", RegexOption.IGNORE_CASE) to "sets its own User-Agent",
        Regex("""Mac\.getInstance|HmacSHA|Signature\.getInstance""") to "signs requests",
        Regex("""com\.huawei\.appmarket|com\.coolapk|ru\.vk\.store|com\.uptodown|com\.apkpure|Ual-Access""") to "names a store's own app",
        Regex("""Pixel \d|SM-[A-Z]\d{3}""") to "names a device",
    )

    /** The fixed model the Galaxy Store is asked for, which PRIVACY.md names, and the list that refuses a User-Agent header. */
    private val allowed = setOf("store/SamsungSource.kt" to "names a device", "web/RequestHeaders.kt" to "sets its own User-Agent")

    @Test
    fun noSourceBorrowsAnotherClientsIdentity() {
        assertTrue("run from the core module", sources.isDirectory)
        val found = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val name = file.relativeTo(sources).path
            val text = file.readText()
            forbidden.filter { (pattern, why) -> pattern.containsMatchIn(text) && (name to why) !in allowed }.map { "$name ${it.second}" }
        }.toList()
        assertEquals(emptyList<String>(), found)
    }
}
