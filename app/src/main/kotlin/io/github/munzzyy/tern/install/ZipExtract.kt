package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.core.apk.ApkFormatException
import io.github.munzzyy.tern.core.apk.ZipEntry
import io.github.munzzyy.tern.core.apk.ZipIndex
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Streams one zip entry to [target], checking its declared size and CRC, without trusting its name. */
object ZipExtract {
    private const val CHUNK = 64 * 1024
    private const val MAX_IDLE = 16

    fun extract(index: ZipIndex, entry: ZipEntry, target: File, maxBytes: Long) {
        if (entry.flags and 1 != 0) throw ApkFormatException("${entry.name} is encrypted")
        if (entry.uncompressedSize > maxBytes) throw ApkFormatException("${entry.name} is larger than $maxBytes bytes")
        val start = index.dataOffset(entry)
        val crc = CRC32()
        var written = 0L
        try {
            FileOutputStream(target).use { out ->
                when (entry.method) {
                    ZipEntry.METHOD_STORED -> {
                        if (entry.compressedSize != entry.uncompressedSize) throw ApkFormatException("${entry.name}: stored sizes differ")
                        var at = 0L
                        while (at < entry.compressedSize) {
                            val n = minOf(CHUNK.toLong(), entry.compressedSize - at).toInt()
                            val bytes = index.source.read(start + at, n)
                            out.write(bytes)
                            crc.update(bytes)
                            at += n
                        }
                        written = at
                    }
                    ZipEntry.METHOD_DEFLATED -> written = inflate(index, entry, start, out, crc)
                    else -> throw ApkFormatException("${entry.name} uses compression method ${entry.method}")
                }
            }
            if (written != entry.uncompressedSize) throw ApkFormatException("${entry.name} is $written bytes, the directory says ${entry.uncompressedSize}")
            if (crc.value != entry.crc32) throw ApkFormatException("${entry.name} fails its CRC check")
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun inflate(index: ZipIndex, entry: ZipEntry, start: Long, out: FileOutputStream, crc: CRC32): Long {
        val inflater = Inflater(true)
        try {
            val output = ByteArray(CHUNK)
            var consumed = 0L
            var produced = 0L
            var idle = 0
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (consumed >= entry.compressedSize) {
                        if (consumed == entry.compressedSize) {
                            inflater.setInput(ByteArray(1))
                            consumed++
                        } else {
                            throw ApkFormatException("${entry.name} ends before its data does")
                        }
                    } else {
                        val n = minOf(CHUNK.toLong(), entry.compressedSize - consumed).toInt()
                        inflater.setInput(index.source.read(start + consumed, n))
                        consumed += n
                    }
                }
                val n = try {
                    inflater.inflate(output)
                } catch (e: DataFormatException) {
                    throw ApkFormatException("${entry.name} is corrupt", e)
                }
                if (n == 0 && inflater.needsDictionary()) throw ApkFormatException("${entry.name} needs a preset dictionary")
                idle = if (n == 0 && !inflater.needsInput()) idle + 1 else 0
                if (idle > MAX_IDLE) throw ApkFormatException("${entry.name} does not inflate")
                produced += n
                if (produced > entry.uncompressedSize) throw ApkFormatException("${entry.name} inflates past its declared size")
                out.write(output, 0, n)
                crc.update(output, 0, n)
            }
            return produced
        } finally {
            inflater.end()
        }
    }
}
