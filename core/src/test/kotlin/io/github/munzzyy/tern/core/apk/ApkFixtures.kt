package io.github.munzzyy.tern.core.apk

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.testing.Fixtures
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream

object ApkFixtures {
    val expected: JsonObject by lazy { Json.parseObject(Fixtures.text("apk/expected.json")) }

    val apkNames: List<String> by lazy { expected.fields.keys.sorted() }

    fun bytes(name: String): ByteArray = Fixtures.bytes("apk/$name")

    fun zip(vararg entries: Triple<String, ByteArray, Boolean>, comment: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            comment?.let { zip.setComment(it) }
            for ((name, data, stored) in entries) {
                val entry = java.util.zip.ZipEntry(name)
                if (stored) {
                    entry.method = java.util.zip.ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun stored(name: String, data: ByteArray) = Triple(name, data, true)

    fun deflated(name: String, data: ByteArray) = Triple(name, data, false)

    /**
     * Rebuilds a signed fixture with [padding] stored bytes between its last entry and its signing
     * block, the layout of a real multi-megabyte APK. The signature no longer matches, which the
     * inspector never checks anyway.
     */
    fun padded(apk: ByteArray, padding: Int): ByteArray {
        val index = ZipIndex.open(BytesSource(apk))
        val cdOffset = index.centralDirectoryOffset.toInt()
        val blockSize = if (String(apk, cdOffset - 16, 16, Charsets.US_ASCII) == "APK Sig Block 42") {
            (apk.u32(cdOffset - 24) + 8).toInt()
        } else {
            0
        }
        val entriesEnd = cdOffset - blockSize
        val name = "assets/pad.bin".toByteArray()
        val data = ByteArray(padding) { (it * 31 + 7).toByte() }
        val crc = CRC32().apply { update(data) }.value
        val local = le(0x04034b50, 4) + le(10, 2) + le(0, 2) + le(0, 2) + le(0, 4) + le(crc, 4) +
            le(padding.toLong(), 4) + le(padding.toLong(), 4) + le(name.size.toLong(), 2) + le(0, 2) + name + data
        val central = le(0x02014b50, 4) + le(10, 2) + le(10, 2) + le(0, 2) + le(0, 2) + le(0, 4) + le(crc, 4) +
            le(padding.toLong(), 4) + le(padding.toLong(), 4) + le(name.size.toLong(), 2) + le(0, 2) + le(0, 2) +
            le(0, 2) + le(0, 2) + le(0, 4) + le(entriesEnd.toLong(), 4) + name
        val oldCentral = apk.copyOfRange(cdOffset, cdOffset + index.centralDirectorySize.toInt())
        val newCdOffset = cdOffset + local.size
        val newCentral = oldCentral + central
        val eocd = le(0x06054b50, 4) + le(0, 2) + le(0, 2) + le(index.entries.size + 1L, 2) + le(index.entries.size + 1L, 2) +
            le(newCentral.size.toLong(), 4) + le(newCdOffset.toLong(), 4) + le(0, 2)
        return apk.copyOfRange(0, entriesEnd) + local + apk.copyOfRange(entriesEnd, cdOffset) + newCentral + eocd
    }

    fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
}
