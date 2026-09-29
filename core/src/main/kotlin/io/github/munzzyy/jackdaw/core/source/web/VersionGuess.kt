package io.github.munzzyy.jackdaw.core.source.web

internal object VersionGuess {
    private val PATTERN = Regex("\\d+(\\.\\d+){1,4}([-._]?(alpha|beta|rc)\\d*)?", RegexOption.IGNORE_CASE)

    fun find(text: String): String? = PATTERN.findAll(text.take(4000)).maxByOrNull { it.value.length }?.value
}
