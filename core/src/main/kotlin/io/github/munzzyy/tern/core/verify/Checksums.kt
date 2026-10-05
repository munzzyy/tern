package io.github.munzzyy.tern.core.verify

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.Release

/** A sum the publisher gave for a file, and the checksum file it was read from; null when the source or the notes gave it. */
data class ExpectedSum(val sha256: String, val file: Asset?)

object Checksums {
    private const val MAX_CHARS = 1024 * 1024
    private const val MAX_LINES = 5000
    private const val PROXIMITY_WINDOW = 60

    /** How many checksum files are read for one file, at most. */
    private const val MAX_FETCHES = 3
    private val SHARED_SUMS_NAMES = setOf("sha256sums", "sha256sums.txt", "checksums.txt", "checksums-sha256.txt")
    private val PACKAGE_ENDINGS = listOf(".apk", ".apks", ".xapk")
    private val OTHER_DIGESTS = listOf("sha512", "sha384", "sha1", "md5", "blake")

    private val HEX64 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
    private val GNU_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+?)\\s*$")
    private val BSD_LINE = Regex("^SHA256\\s*\\(([^)]+)\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9_.+\\-]*\\.[A-Za-z0-9]{1,12}")

    /**
     * The sums in [text] by the file they are for, with "" for a sum that names no file.
     * A name that is given two different sums is left out, because nothing says which one is meant.
     */
    fun parse(text: String): Map<String, String> {
        val capped = text.take(MAX_CHARS)
        val lines = capped.lineSequence().take(MAX_LINES).toList()
        val result = LinkedHashMap<String, String>()
        val unclear = HashSet<String>()
        fun put(name: String, hex: String) {
            val before = result.put(name, hex)
            if (before != null && before != hex) unclear += name
        }
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val bsd = BSD_LINE.find(line)
            if (bsd != null) {
                put(bsd.groupValues[1].trim(), bsd.groupValues[2].lowercase())
                continue
            }
            val gnu = GNU_LINE.find(line)
            if (gnu != null) {
                put(gnu.groupValues[2].trim(), gnu.groupValues[1].lowercase())
                continue
            }
            for (m in HEX64.findAll(line)) {
                val hex = m.value.lowercase()
                val name = nearestFilename(line, m.range.first, m.range.last)
                    ?: nearbyFilename(lines, index + 1)
                    ?: nearbyFilename(lines, index - 1)
                put(name ?: "", hex)
            }
        }
        unclear.forEach(result::remove)
        return result
    }

    private fun nearestFilename(line: String, hexStart: Int, hexEnd: Int): String? {
        val beforeStart = maxOf(0, hexStart - PROXIMITY_WINDOW)
        val afterEnd = minOf(line.length, hexEnd + 1 + PROXIMITY_WINDOW)
        val before = line.substring(beforeStart, hexStart)
        val after = line.substring(minOf(hexEnd + 1, line.length), afterEnd)
        val beforeMatch = FILENAME.findAll(before).lastOrNull()?.takeIf { !isHexOnly(it.value) }
        val afterMatch = FILENAME.find(after)?.takeIf { !isHexOnly(it.value) }
        return when {
            beforeMatch != null && afterMatch != null -> {
                val distBefore = before.length - beforeMatch.range.last
                val distAfter = afterMatch.range.first
                if (distBefore <= distAfter) beforeMatch.value else afterMatch.value
            }
            beforeMatch != null -> beforeMatch.value
            afterMatch != null -> afterMatch.value
            else -> null
        }
    }

    fun expectedFor(release: Release, asset: Asset, fetch: (Asset) -> String): String? = expected(release, asset, fetch)?.sha256

    /**
     * The sum for [asset]: its own, else from the first of [candidatesFor] that gives one, reading
     * no more than three of them, else from the release's notes. A file made for [asset] alone may
     * hold a bare sum; any other has to name the file.
     */
    fun expected(release: Release, asset: Asset, fetch: (Asset) -> String): ExpectedSum? {
        asset.sha256?.let { return ExpectedSum(it, null) }
        val own = ownNames(release, asset)
        for (sums in candidatesFor(release, asset).take(MAX_FETCHES)) {
            val parsed = parse(fetch(sums))
            val sha = named(parsed, asset.name) ?: parsed[""]?.takeIf { sums.name in own }
            if (sha != null) return ExpectedSum(sha, sums)
        }
        return release.notes?.let { notes -> named(parse(notes), asset.name) }?.let { ExpectedSum(it, null) }
    }

    /**
     * The checksum files of [release] that may hold the sum of [asset], in the order they are
     * read: those made for it alone, then the shared names, then any other whose name says it
     * holds SHA-256 sums.
     */
    fun candidatesFor(release: Release, asset: Asset): List<Asset> {
        val sums = release.assets.filter { it.kind == AssetKind.CHECKSUM }
        val own = ownNames(release, asset)
        val first = own.mapNotNull { name -> sums.firstOrNull { it.name == name } }
        val shared = sums.filter { it.name.lowercase() in SHARED_SUMS_NAMES }
        val rest = sums.filter { sum ->
            val name = sum.name.lowercase()
            ("sha256" in name || "checksum" in name) && OTHER_DIGESTS.none { it in name }
        }
        return (first + shared + rest).distinct()
    }

    /** Names a checksum file made for [asset] alone goes by. A name shared with another file of the release is not one. */
    private fun ownNames(release: Release, asset: Asset): List<String> {
        val names = mutableListOf("${asset.name}.sha256", "${asset.name}.sha256sum", "${asset.name}.sha256.txt")
        val stem = PACKAGE_ENDINGS.firstOrNull { asset.name.endsWith(it, ignoreCase = true) }?.let { asset.name.dropLast(it.length) }
        val alone = stem != null && release.assets.none { it.name != asset.name && it.kind != AssetKind.CHECKSUM && it.kind != AssetKind.SIGNATURE && it.name.substringBeforeLast('.') == stem }
        if (alone) names += "$stem.sha256"
        return names
    }

    /** Sums files often list a file with the folder it was built in; the file name alone decides. */
    private fun named(sums: Map<String, String>, name: String): String? {
        sums[name]?.let { return it }
        val matches = sums.filterKeys { it.replace('\\', '/').substringAfterLast('/') == name }.values.toSet()
        return matches.singleOrNull()
    }

    private fun nearbyFilename(lines: List<String>, at: Int): String? =
        lines.getOrNull(at)?.let { FILENAME.find(stripMarkdown(it))?.value }

    private fun stripMarkdown(line: String): String =
        line.replace("`", "").replace("|", " ").replace(Regex("^[\\s*\\-+]+"), "")

    private fun isHexOnly(name: String): Boolean = name.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
