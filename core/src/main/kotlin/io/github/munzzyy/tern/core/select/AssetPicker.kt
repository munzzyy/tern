package io.github.munzzyy.tern.core.select

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern

/** One thing the file's name said that moved its score. [detail] is the processor or device type it named. */
data class PickReason(val kind: Kind, val detail: String? = null) {
    enum class Kind { ABI_MATCH, ABI_MISMATCH, UNIVERSAL, NO_ABI, VARIANT_MATCH, VARIANT_MISMATCH, DEBUG_BUILD, TEST_BUILD, UNSIGNED }
}

data class Pick(val asset: Asset, val score: Int, val reasons: List<PickReason>)

class AssetPolicyException(message: String) : Exception(message)

object AssetPicker {
    private val SPECIAL_ABI_TOKENS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    private val ABI_ALIASES = mapOf(
        "arm64-v8a" to "arm64-v8a", "arm64" to "arm64-v8a", "aarch64" to "arm64-v8a", "armv8" to "arm64-v8a",
        "arm64v8" to "arm64-v8a", "arm64v8a" to "arm64-v8a",
        "armeabi-v7a" to "armeabi-v7a", "armv7" to "armeabi-v7a", "armv7a" to "armeabi-v7a",
        "arm32" to "armeabi-v7a", "armeabi" to "armeabi-v7a", "arm32v7" to "armeabi-v7a",
        "armeabiv7a" to "armeabi-v7a", "armhf" to "armeabi-v7a", "arm" to "armeabi-v7a",
        "x86_64" to "x86_64", "x64" to "x86_64", "amd64" to "x86_64", "x8664" to "x86_64",
        "x86" to "x86", "i686" to "x86", "i386" to "x86",
        "universal" to "any", "all" to "any", "fat" to "any", "noarch" to "any",
    )

    private val VARIANT_TOKENS = setOf("tv", "wear", "automotive")
    private val DEBUG_REASONS = mapOf(
        "debug" to PickReason.Kind.DEBUG_BUILD,
        "test" to PickReason.Kind.TEST_BUILD,
        "tests" to PickReason.Kind.TEST_BUILD,
        "unsigned" to PickReason.Kind.UNSIGNED,
    )

    private const val MAX_NAME_LENGTH = 512
    private const val ABI_BASE_SCORE = 400
    private const val ABI_STEP = 20
    private const val NO_ABI_BONUS = 60
    private const val ABI_MISMATCH_PENALTY = 1000
    private const val VARIANT_MATCH_BONUS = 80
    private const val VARIANT_MISMATCH_PENALTY = 400
    private const val DEBUG_PENALTY = 300
    private const val APK_OVER_BUNDLE_BONUS = 40

    fun rank(assets: List<Asset>, device: DeviceProfile, policy: AssetPolicy): List<Pick> {
        val include = compile(policy.include)
        val exclude = compile(policy.exclude)
        val deviceAbis = device.abis.map { canonicalAbi(it.lowercase()) }
        val deviceVariant = when {
            device.television -> "tv"
            device.watch -> "wear"
            device.automotive -> "automotive"
            else -> null
        }

        val picks = try {
            SafePattern.watched("file filters") {
                assets
                    .filter { it.kind == AssetKind.APK || it.kind == AssetKind.BUNDLE }
                    .mapNotNull { asset -> rankOne(asset, deviceAbis, deviceVariant, policy, include, exclude) }
            }
        } catch (e: PatternException) {
            throw AssetPolicyException(e.message ?: "The file filter could not be applied")
        }

        return picks.sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.asset.name })
    }

    private fun rankOne(
        asset: Asset,
        deviceAbis: List<String>,
        deviceVariant: String?,
        policy: AssetPolicy,
        include: SafePattern?,
        exclude: SafePattern?,
    ): Pick? {
        val name = asset.name.take(MAX_NAME_LENGTH)
        try {
            if (include != null && !include.matches(name)) return null
            if (exclude != null && exclude.matches(name)) return null
        } catch (e: PatternException) {
            throw AssetPolicyException(e.message ?: "The file filter could not be applied")
        }

        val tokens = tokenize(name)
        var score = 0
        val reasons = mutableListOf<PickReason>()

        val namedAbis = tokens.mapNotNull { ABI_ALIASES[it] }.distinct()
        val realAbis = namedAbis.filterNot { it == "any" }
        if (realAbis.isNotEmpty()) {
            val matches = realAbis.filter { it in deviceAbis }
            if (matches.isEmpty()) {
                if (policy.matchDevice) return null
                score -= ABI_MISMATCH_PENALTY
                reasons += PickReason(PickReason.Kind.ABI_MISMATCH, realAbis.joinToString())
            } else {
                val bestIndex = matches.minOf { deviceAbis.indexOf(it) }
                val bestAbi = matches.first { deviceAbis.indexOf(it) == bestIndex }
                score += ABI_BASE_SCORE - bestIndex * ABI_STEP
                reasons += PickReason(PickReason.Kind.ABI_MATCH, bestAbi)
            }
        } else if ("any" in namedAbis) {
            score += NO_ABI_BONUS
            reasons += PickReason(PickReason.Kind.UNIVERSAL)
        } else {
            score += NO_ABI_BONUS - 1
            reasons += PickReason(PickReason.Kind.NO_ABI)
        }

        for (variant in tokens.filter { it in VARIANT_TOKENS }.distinct()) {
            if (variant == deviceVariant) {
                score += VARIANT_MATCH_BONUS
                reasons += PickReason(PickReason.Kind.VARIANT_MATCH, variant)
            } else {
                score -= VARIANT_MISMATCH_PENALTY
                reasons += PickReason(PickReason.Kind.VARIANT_MISMATCH, variant)
            }
        }

        for (token in tokens.distinct()) {
            val kind = DEBUG_REASONS[token] ?: continue
            score -= DEBUG_PENALTY
            if (reasons.none { it.kind == kind }) reasons += PickReason(kind)
        }

        if (asset.kind == AssetKind.APK) {
            score += APK_OVER_BUNDLE_BONUS
        }

        return Pick(asset, score, reasons)
    }

    private fun canonicalAbi(abi: String): String = ABI_ALIASES[abi] ?: abi

    private fun tokenize(name: String): List<String> {
        var remaining = name.lowercase()
        val found = mutableListOf<String>()
        for (special in SPECIAL_ABI_TOKENS) {
            val boundary = Regex("(?<![a-z0-9])" + Regex.escape(special) + "(?![a-z0-9])")
            if (boundary.containsMatchIn(remaining)) {
                found += special
                remaining = boundary.replace(remaining, " ")
            }
        }
        val rest = remaining.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return found + rest
    }

    private fun compile(pattern: String?): SafePattern? = try {
        SafePattern.compileOrNull(pattern)
    } catch (e: PatternException) {
        throw AssetPolicyException(e.message ?: "The file filter is not a valid pattern")
    }
}
