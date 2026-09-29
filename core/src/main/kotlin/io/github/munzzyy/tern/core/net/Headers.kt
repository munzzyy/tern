package io.github.munzzyy.tern.core.net

class Headers private constructor(private val entries: List<Pair<String, String>>) {
    operator fun get(name: String): String? = entries.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun names(): Set<String> = entries.mapTo(LinkedHashSet()) { it.first.lowercase() }

    fun toList(): List<Pair<String, String>> = entries

    companion object {
        val EMPTY = Headers(emptyList())

        fun of(vararg pairs: Pair<String, String>): Headers = Headers(pairs.toList())

        fun of(pairs: List<Pair<String, String>>): Headers = Headers(pairs.toList())

        fun of(map: Map<String, String>): Headers = Headers(map.map { it.key to it.value })
    }
}
