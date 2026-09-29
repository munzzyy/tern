package io.github.munzzyy.jackdaw.core.apk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class HostileInputTest {
    private val seed = 0x6a61636b64617721L
    private val mutationsPerFixture = 2500
    private val targets = listOf(
        "app-v1.apk", "app-jar-only.apk", "app-rotated.apk", "app-zip64.apk", "app-v2-only.apk",
        "split-config.arm64_v8a.apk", "bundle.xapk", "bundle.apks",
    )

    private fun mutate(original: ByteArray, random: Random): ByteArray {
        val bytes = original.copyOf()
        val hotspots = listOf(0, bytes.size - 4096, ZipIndex.open(BytesSource(original)).centralDirectoryOffset.toInt() - 4096)
        fun offset(width: Int): Int {
            val base = if (random.nextInt(3) == 0) 0 else hotspots[random.nextInt(hotspots.size)].coerceAtLeast(0)
            val span = if (base == 0) bytes.size else minOf(8192, bytes.size - base)
            return (base + random.nextInt(maxOf(1, span - width))).coerceIn(0, maxOf(0, bytes.size - width))
        }
        return when (random.nextInt(4)) {
            0 -> bytes.also { repeat(1 + random.nextInt(8)) { _ -> val at = offset(1); bytes[at] = (bytes[at].toInt() xor (1 + random.nextInt(255))).toByte() } }
            1 -> bytes.copyOf(random.nextInt(bytes.size))
            2 -> bytes.also {
                val value = listOf(0L, 0x7fffffffL, 0xffffffffL)[random.nextInt(3)]
                ApkFixtures.le(value, 4).copyInto(bytes, offset(4))
            }
            else -> bytes.also {
                val value = listOf(0L, 0x7fffL, 0xffffL)[random.nextInt(3)]
                ApkFixtures.le(value, 2).copyInto(bytes, offset(2))
            }
        }
    }

    private fun survive(label: String, block: () -> Unit) {
        val started = System.nanoTime()
        try {
            block()
        } catch (e: IOException) {
            assertTrue(e.message != null)
        } catch (e: Throwable) {
            fail("$label threw ${e::class.java.name}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}")
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        if (millis > 1000) fail("$label took $millis ms")
    }

    @Test
    fun mutatedFixturesFailOnlyWithIoExceptions() {
        val random = Random(seed)
        for (name in targets) {
            val original = ApkFixtures.bytes(name)
            val bundle = name.startsWith("bundle")
            repeat(mutationsPerFixture) { i ->
                val mutated = mutate(original, random)
                survive("$name mutation $i") {
                    if (bundle) BundleIndex.read(BytesSource(mutated)) else ApkInspector.inspect(BytesSource(mutated))
                }
            }
        }
    }

    @Test
    fun mutatedManifestsFailOnlyWithApkFormatException() {
        val random = Random(seed + 1)
        for (name in listOf("app-v1.apk", "split-config.en.apk")) {
            val index = ZipIndex.open(BytesSource(ApkFixtures.bytes(name)))
            val xml = index.read(index.find("AndroidManifest.xml")!!, 1 shl 20)
            repeat(mutationsPerFixture) { i ->
                val mutated = xml.copyOf()
                when (random.nextInt(3)) {
                    0 -> repeat(1 + random.nextInt(4)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
                    1 -> ApkFixtures.le(listOf(0L, 0x7fffffffL, 0xffffffffL)[random.nextInt(3)], 4).copyInto(mutated, random.nextInt(mutated.size - 4))
                    else -> ApkFixtures.le(listOf(0L, 0x7fffL, 0xffffL)[random.nextInt(3)], 2).copyInto(mutated, random.nextInt(mutated.size - 2))
                }
                survive("$name manifest mutation $i") { BinaryManifest.parse(mutated) }
            }
        }
    }

    @Test
    fun mutatedSigningBlocksFailOnlyWithIoExceptions() {
        val random = Random(seed + 2)
        for (name in listOf("app-v1.apk", "app-rotated.apk")) {
            val original = ApkFixtures.bytes(name)
            val cd = ZipIndex.open(BytesSource(original)).centralDirectoryOffset.toInt()
            val blockStart = cd - (original.u32(cd - 24).toInt() + 8)
            repeat(mutationsPerFixture) { i ->
                val mutated = original.copyOf()
                val at = blockStart + random.nextInt(cd - blockStart - 4)
                if (random.nextBoolean()) {
                    mutated[at] = random.nextInt(256).toByte()
                } else {
                    ApkFixtures.le(listOf(0L, 0x7fffffffL, 0xffffffffL, 1L, 4L)[random.nextInt(5)], 4).copyInto(mutated, at)
                }
                survive("$name signing block mutation $i") { ApkInspector.inspect(BytesSource(mutated)) }
            }
        }
    }
}
