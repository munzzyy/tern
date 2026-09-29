package io.github.munzzyy.tern.core.apk

import java.security.MessageDigest

/** Where the four parts of a signed file lie. The signing block ends where the central directory starts. */
internal class ApkLayout(val blockOffset: Long, val directoryOffset: Long, val endRecordOffset: Long, val fileSize: Long)

/**
 * The digest that the APK signature schemes v2 and v3 sign: over the entries, the central
 * directory and the end record, each cut into chunks of 1 MiB, with the signing block left out.
 * The file is read one chunk at a time.
 */
internal class ContentDigests(private val source: RandomAccessSource, private val layout: ApkLayout) {
    private val computed = HashMap<ContentDigest, ByteArray>()

    fun of(kind: ContentDigest): ByteArray = computed.getOrPut(kind) {
        compute(kind.algorithm ?: throw NotCheckedHere("a verity digest is not computed here"))
    }

    private fun compute(algorithm: String): ByteArray {
        val sections = listOf(
            0L to layout.blockOffset,
            layout.directoryOffset to layout.endRecordOffset - layout.directoryOffset,
            layout.endRecordOffset to layout.fileSize - layout.endRecordOffset,
        )
        val chunks = sections.sumOf { (_, length) -> (length + CHUNK - 1) / CHUNK }
        if (chunks > MAX_CHUNKS) throw ApkFormatException("$chunks chunks to digest")
        val whole = MessageDigest.getInstance(algorithm)
        whole.update(WHOLE_PREFIX)
        whole.update(u32(chunks))
        val part = MessageDigest.getInstance(algorithm)
        for ((start, length) in sections) {
            var at = 0L
            while (at < length) {
                val size = minOf(CHUNK, length - at).toInt()
                val bytes = source.read(start + at, size)
                if (start == layout.endRecordOffset) pointAtBlock(bytes)
                part.update(CHUNK_PREFIX)
                part.update(u32(size.toLong()))
                part.update(bytes)
                whole.update(part.digest())
                at += size
            }
        }
        return whole.digest()
    }

    // The end record fits one chunk: a zip comment is at most 65535 bytes long.
    private fun pointAtBlock(endRecord: ByteArray) {
        if (endRecord.size < END_RECORD || layout.fileSize - layout.endRecordOffset > CHUNK) throw ApkFormatException("End record of ${endRecord.size} bytes")
        u32(layout.blockOffset).copyInto(endRecord, DIRECTORY_OFFSET_FIELD)
    }

    private fun u32(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private companion object {
        const val CHUNK = 1024L * 1024
        const val MAX_CHUNKS = 1024L * 1024
        const val CHUNK_PREFIX = 0xa5.toByte()
        const val WHOLE_PREFIX = 0x5a.toByte()
        const val END_RECORD = 22
        const val DIRECTORY_OFFSET_FIELD = 16
    }
}
