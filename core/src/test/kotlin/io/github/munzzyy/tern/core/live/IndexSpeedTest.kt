package io.github.munzzyy.tern.core.live

import io.github.munzzyy.tern.core.json.JsonReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest

/** Times a walk over a real repository index: ./gradlew :core:test -Dtern.index=/path/to/index-v2.json */
class IndexSpeedTest {
    @Test
    fun walksALargeIndex() {
        val path = System.getProperty("tern.index")
        assumeTrue("no index given", !path.isNullOrBlank())
        val file = File(path)
        repeat(3) { round ->
            val started = System.nanoTime()
            val digest = MessageDigest.getInstance("SHA-256")
            var packages = 0
            var found = false
            DigestInputStream(file.inputStream().buffered(64 * 1024), digest).use { stream ->
                val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8), maxDepth = 96)
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "packages") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            packages++
                            if (name == "org.fdroid.fdroid") {
                                reader.readValue()
                                found = true
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                reader.requireEndOfDocument()
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            val mb = file.length() / (1024.0 * 1024.0)
            println("round $round: ${"%.1f".format(mb)} MiB, $packages packages, found=$found, $ms ms, ${"%.1f".format(mb / (ms / 1000.0))} MiB/s")
        }
    }
}
