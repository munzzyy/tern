package io.github.munzzyy.jackdaw.core.apk

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class ZipEntry(
    val name: String,
    val flags: Int,
    val method: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
    val isStored: Boolean get() = method == METHOD_STORED

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
    }
}

/** The central directory of a zip file. Entry names are data, never paths: nothing here touches the file system. */
class ZipIndex private constructor(
    val source: RandomAccessSource,
    val entries: List<ZipEntry>,
    val centralDirectoryOffset: Long,
    val centralDirectorySize: Long,
) {
    private val byName = entries.associateBy { it.name }

    fun find(name: String): ZipEntry? = byName[name]

    /** Offset of the entry's data in [source], after its local header. */
    fun dataOffset(entry: ZipEntry): Long {
        val header = source.read(entry.localHeaderOffset, LOCAL_HEADER)
        if (header.u32(0) != LOCAL_SIGNATURE) throw ApkFormatException("No local header for ${entry.name}")
        val nameLength = header.u16(26)
        val extraLength = header.u16(28)
        val name = source.read(entry.localHeaderOffset + LOCAL_HEADER, nameLength)
        if (!name.contentEquals(entry.name.toByteArray(Charsets.UTF_8))) throw ApkFormatException("Local header name differs for ${entry.name}")
        val start = entry.localHeaderOffset + LOCAL_HEADER + nameLength + extraLength
        if (start > centralDirectoryOffset || entry.compressedSize > centralDirectoryOffset - start) {
            throw ApkFormatException("Data of ${entry.name} runs into the central directory")
        }
        return start
    }

    fun read(entry: ZipEntry, maxBytes: Int): ByteArray {
        if (entry.flags and FLAG_ENCRYPTED != 0) throw ApkFormatException("${entry.name} is encrypted")
        if (entry.uncompressedSize > maxBytes) throw ApkFormatException("${entry.name} is ${entry.uncompressedSize} bytes, limit $maxBytes")
        val start = dataOffset(entry)
        val data = when (entry.method) {
            ZipEntry.METHOD_STORED -> {
                if (entry.compressedSize != entry.uncompressedSize) throw ApkFormatException("Stored ${entry.name} has two sizes")
                source.read(start, entry.compressedSize.toInt())
            }
            ZipEntry.METHOD_DEFLATED -> {
                if (entry.compressedSize > minOf(maxBytes.toLong() + DEFLATE_SLACK, Int.MAX_VALUE.toLong())) throw ApkFormatException("${entry.name} is too large compressed")
                inflate(source.read(start, entry.compressedSize.toInt()), entry.uncompressedSize.toInt(), entry.name)
            }
            else -> throw ApkFormatException("${entry.name} uses unsupported method ${entry.method}")
        }
        val crc = CRC32().apply { update(data) }.value
        if (crc != entry.crc32) throw ApkFormatException("CRC mismatch in ${entry.name}")
        return data
    }

    private fun inflate(compressed: ByteArray, declared: Int, name: String): ByteArray {
        val out = ByteArray(declared)
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            var produced = 0
            var stalls = 0
            while (produced < declared) {
                val n = inflater.inflate(out, produced, declared - produced)
                produced += n
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary() || ++stalls > 4) {
                        throw ApkFormatException("$name inflates to $produced bytes, declared $declared")
                    }
                }
            }
            if (!inflater.finished() && inflater.inflate(ByteArray(1)) > 0) throw ApkFormatException("$name inflates past its declared size")
            return out
        } catch (e: DataFormatException) {
            throw ApkFormatException("$name is not valid deflate data", e)
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val MAX_ENTRIES = 100_000
        const val MAX_CENTRAL_DIRECTORY = 32 * 1024 * 1024
        const val MAX_NAME = 4096
        private const val EOCD = 22
        private const val MAX_COMMENT = 65535
        private const val LOCAL_HEADER = 30
        private const val CENTRAL_HEADER = 46
        private const val FLAG_ENCRYPTED = 1
        private const val DEFLATE_SLACK = 65536
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val LOCATOR_SIGNATURE = 0x07064b50L
        private const val EOCD64_SIGNATURE = 0x06064b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val U16_MAX = 0xffff
        private const val U32_MAX = 0xffffffffL

        fun open(source: RandomAccessSource): ZipIndex {
            val size = source.size
            if (size < EOCD) throw ApkFormatException("Too small to be a zip file")
            val tailLength = minOf(size, (EOCD + MAX_COMMENT).toLong()).toInt()
            val tailStart = size - tailLength
            val tail = source.read(tailStart, tailLength)
            val at = findEocd(tail) ?: throw ApkFormatException("No end of central directory record")
            val eocdOffset = tailStart + at
            if (tail.u16(at + 4) != 0 || tail.u16(at + 6) != 0) throw ApkFormatException("Multi-disk zip files are not supported")
            var count = tail.u16(at + 10).toLong()
            var cdSize = tail.u32(at + 12)
            var cdOffset = tail.u32(at + 16)
            var cdLimit = eocdOffset
            val locatorOffset = eocdOffset - 20
            if (locatorOffset >= 0 && source.read(locatorOffset, 20).let { it.u32(0) == LOCATOR_SIGNATURE }) {
                val locator = LeReader(source.read(locatorOffset, 20))
                locator.skip(8)
                val eocd64Offset = locator.u64()
                if (eocd64Offset > locatorOffset - 56) throw ApkFormatException("ZIP64 end record out of place")
                val record = LeReader(source.read(eocd64Offset, 56))
                if (record.u32() != EOCD64_SIGNATURE) throw ApkFormatException("Bad ZIP64 end record")
                record.skip(8 + 2 + 2 + 4 + 4 + 8)
                count = record.u64()
                cdSize = record.u64()
                cdOffset = record.u64()
                cdLimit = eocd64Offset
            } else if (count == U16_MAX.toLong() || cdSize == U32_MAX || cdOffset == U32_MAX) {
                throw ApkFormatException("ZIP64 markers without a ZIP64 locator")
            }
            if (count > MAX_ENTRIES) throw ApkFormatException("$count entries, limit $MAX_ENTRIES")
            if (cdSize > MAX_CENTRAL_DIRECTORY) throw ApkFormatException("Central directory of $cdSize bytes, limit $MAX_CENTRAL_DIRECTORY")
            if (cdOffset > cdLimit || cdSize > cdLimit - cdOffset) throw ApkFormatException("Central directory lies outside the file")
            val central = source.read(cdOffset, cdSize.toInt())
            val entries = parseCentral(central, count.toInt(), cdOffset)
            return ZipIndex(source, entries, cdOffset, cdSize)
        }

        private fun findEocd(tail: ByteArray): Int? {
            var i = tail.size - EOCD
            while (i >= 0) {
                if (tail.u32(i) == EOCD_SIGNATURE && tail.u16(i + 20) == tail.size - i - EOCD) return i
                i--
            }
            return null
        }

        private fun parseCentral(central: ByteArray, count: Int, cdOffset: Long): List<ZipEntry> {
            val reader = LeReader(central)
            val entries = ArrayList<ZipEntry>(minOf(count, 1024))
            val names = HashSet<String>()
            repeat(count) {
                if (reader.u32() != CENTRAL_SIGNATURE) throw ApkFormatException("Bad central directory entry ${entries.size}")
                reader.skip(4)
                val flags = reader.u16()
                val method = reader.u16()
                reader.skip(4)
                val crc = reader.u32()
                var compressed = reader.u32()
                var uncompressed = reader.u32()
                val nameLength = reader.u16()
                val extraLength = reader.u16()
                val commentLength = reader.u16()
                reader.skip(8)
                var localOffset = reader.u32()
                if (nameLength > MAX_NAME) throw ApkFormatException("Entry name of $nameLength bytes")
                val name = decodeName(reader.bytes(nameLength))
                val extra = reader.slice(extraLength)
                reader.skip(commentLength)
                if (uncompressed == U32_MAX || compressed == U32_MAX || localOffset == U32_MAX) {
                    val zip64 = zip64Extra(extra) ?: throw ApkFormatException("$name needs a ZIP64 extra field")
                    if (uncompressed == U32_MAX) uncompressed = zip64.u64()
                    if (compressed == U32_MAX) compressed = zip64.u64()
                    if (localOffset == U32_MAX) localOffset = zip64.u64()
                }
                if (localOffset > cdOffset || compressed > cdOffset - localOffset - LOCAL_HEADER) {
                    throw ApkFormatException("$name overlaps the central directory or runs past the file")
                }
                if (!names.add(name)) throw ApkFormatException("Duplicate entry $name")
                entries.add(ZipEntry(name, flags, method, crc, compressed, uncompressed, localOffset))
            }
            return entries
        }

        private fun zip64Extra(extra: LeReader): LeReader? {
            while (extra.remaining >= 4) {
                val id = extra.u16()
                val field = extra.slice(extra.u16())
                if (id == 1) return field
            }
            return null
        }

        private fun decodeName(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ApkFormatException("Entry name is not UTF-8", e)
        }
    }
}
