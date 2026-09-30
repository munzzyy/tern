package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.core.apk.ApkFormatException
import io.github.munzzyy.tern.core.apk.FileSource
import io.github.munzzyy.tern.core.apk.ZipIndex
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A file of an app fetched from an address of its own, such as a split beside its base, and the name it goes by. */
data class FetchedPart(val name: String, val file: File)

/**
 * Puts a base and its splits, fetched one by one, into one zip, so that they are chosen and checked
 * exactly as the APKs of a bundle are. A part that is an APK goes in under its own name, made to end
 * in .apk. A part that is a zip, as RuStore serves each file, gives the APKs it holds under their
 * names there. Every name is data and never a path, and a name that comes twice is numbered.
 */
object PartsZip {
    /** More bytes or more APKs than one install may take. */
    class TooLarge(message: String) : Exception(message)

    /**
     * Writes [parts] into [target], using [scratch] for an APK taken out of a zip. Throws
     * [ApkFormatException] for a part that is neither an APK nor a zip that holds one.
     */
    fun join(parts: List<FetchedPart>, target: File, scratch: File, maxBytes: Long, maxApks: Int) {
        val names = HashSet<String>()
        var total = 0L
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            fun add(name: String, file: File) {
                total += file.length()
                if (total > maxBytes) throw TooLarge("The parts are larger than $maxBytes bytes")
                if (names.size >= maxApks) throw TooLarge("The parts hold more than $maxApks APKs")
                val plain = name.trimStart('/')
                var unique = plain.take(MAX_NAME)
                var n = 2
                while (!names.add(unique)) unique = "${n++}/$plain".take(MAX_NAME)
                zip.putStored(unique, file)
            }
            for (part in parts) {
                FileSource(part.file).use { source ->
                    val index = ZipIndex.open(source)
                    if (index.find(MANIFEST) != null) {
                        add(apkName(part.name), part.file)
                        return@use
                    }
                    val apks = index.entries.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    if (apks.isEmpty()) throw ApkFormatException("${part.name} is not an APK and holds none")
                    for (entry in apks) {
                        try {
                            ZipExtract.extract(index, entry, scratch, maxBytes)
                            add(entry.name, scratch)
                        } finally {
                            scratch.delete()
                        }
                    }
                }
            }
        }
    }

    /** [name] as the name of an APK: a split named for its .zip is named for the APK inside. */
    fun apkName(name: String): String = when {
        name.endsWith(".apk", ignoreCase = true) -> name
        name.contains('.') -> name.substringBeforeLast('.') + ".apk"
        else -> "$name.apk"
    }

    /** The name a split fetched from [url] goes by: the last part of its path. */
    fun nameOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        return path.trimEnd('/').substringAfterLast('/').ifEmpty { "split" }
    }

    private const val MANIFEST = "AndroidManifest.xml"
    private const val MAX_NAME = 1024
}

/** Adds [file] as a stored entry, which Android and Tern read in place without unpacking it. */
internal fun ZipOutputStream.putStored(name: String, file: File) {
    val crc = CRC32()
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            crc.update(buffer, 0, n)
        }
    }
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = file.length()
        compressedSize = file.length()
        this.crc = crc.value
    }
    putNextEntry(entry)
    FileInputStream(file).use { it.copyTo(this, 64 * 1024) }
    closeEntry()
}
