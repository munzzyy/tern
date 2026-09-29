package io.github.munzzyy.jackdaw.core.verify

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.Release

object Checksums {
    private const val MAX_CHARS = 1024 * 1024
    private const val MAX_LINES = 5000
    private const val PROXIMITY_WINDOW = 60
    private val SHARED_SUMS_NAMES = setOf("sha256sums", "sha256sums.txt", "checksums.txt", "checksums-sha256.txt")

    private val HEX64 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
    private val GNU_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+?)\\s*$")
    private val BSD_LINE = Regex("^SHA256\\s*\\(([^)]+)\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9_.+\\-]*\\.[A-Za-z0-9]{1,12}")

    fun parse(text: String): Map<String, String> {
        val capped = text.take(MAX_CHARS)
        val lines = capped.lineSequence().take(MAX_LINES).toList()
        val result = LinkedHashMap<String, String>()
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val bsd = BSD_LINE.find(line)
            if (bsd != null) {
                result[bsd.groupValues[1].trim()] = bsd.groupValues[2].lowercase()
                continue
            }
            val gnu = GNU_LINE.find(line)
            if (gnu != null) {
                result[gnu.groupValues[2].trim()] = gnu.groupValues[1].lowercase()
                continue
            }
            for (m in HEX64.findAll(line)) {
                val hex = m.value.lowercase()
                val name = nearestFilename(line, m.range.first, m.range.last)
                    ?: nearbyFilename(lines, index + 1)
                    ?: nearbyFilename(lines, index - 1)
                result[name ?: ""] = hex
            }
        }
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

    fun expectedFor(release: Release, asset: Asset, fetch: (Asset) -> String): String? {
        asset.sha256?.let { return it }

        val siblingNames = setOf("${asset.name}.sha256", "${asset.name}.sha256sum")
        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name in siblingNames }?.let { sibling ->
            val parsed = parse(fetch(sibling))
            named(parsed, asset.name)?.let { return it }
            parsed[""]?.let { return it }
        }

        release.assets.firstOrNull { it.kind == AssetKind.CHECKSUM && it.name.lowercase() in SHARED_SUMS_NAMES }?.let { shared ->
            named(parse(fetch(shared)), asset.name)?.let { return it }
        }

        release.notes?.let { notes -> named(parse(notes), asset.name)?.let { return it } }
        return null
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
