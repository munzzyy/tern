package io.github.munzzyy.tern.ui.theme

import androidx.compose.ui.graphics.Color

/** Sixteen colours a category may take: mid tones that stand out on light and dark surfaces alike. */
val CATEGORY_SWATCHES: List<Int> = listOf(
    0xFFE0605E, 0xFFE8739A, 0xFFB96AC9, 0xFF8E78D8, 0xFF6F86D6, 0xFF4F9BE8, 0xFF3FB0D9, 0xFF35B8B0,
    0xFF4FAE7A, 0xFF86B94E, 0xFFC2BC3A, 0xFFE3B23C, 0xFFEE9A3A, 0xFFE9794B, 0xFFA7836B, 0xFF8C959E,
).map { it.toInt() }

/** The colour of the category [name]: the one picked for it, else the swatch its name falls on, the same every time. */
fun categoryArgb(name: String, picked: Map<String, Int>): Int =
    picked[name] ?: picked.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        ?: CATEGORY_SWATCHES[Math.floorMod(name.trim().lowercase().hashCode(), CATEGORY_SWATCHES.size)]

fun categoryColor(name: String, picked: Map<String, Int>): Color = Color(categoryArgb(name, picked))
