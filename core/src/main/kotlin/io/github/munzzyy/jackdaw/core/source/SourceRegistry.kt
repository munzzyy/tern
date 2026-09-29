package io.github.munzzyy.jackdaw.core.source

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Urls
import java.io.IOException

class SourceRegistry(val sources: List<Source>) {
    fun get(type: String): Source? = sources.firstOrNull { it.type == type }

    fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        for (source in sources) {
            source.match(normalized)?.let { return it }
        }
        return null
    }

    fun detect(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        match(normalized)?.let { return it }
        for (source in sources) {
            val spec = try {
                source.probe(normalized, context)
            } catch (_: IOException) {
                null
            } catch (_: SourceException) {
                null
            }
            if (spec != null) return spec
        }
        return null
    }
}
