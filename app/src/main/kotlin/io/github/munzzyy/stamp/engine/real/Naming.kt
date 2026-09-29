package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.net.Urls

/** Which name a row carries. A name the user typed is never replaced. */
object Naming {
    /**
     * A new app is named after its repository, because that is all that is known. Once it is on
     * the phone it has a name of its own, and that one is used unless the user chose another.
     */
    fun afterInstall(config: AppConfig, label: String?): String {
        if (label.isNullOrBlank()) return config.name
        return if (cameFromTheSource(config)) label else config.name
    }

    private fun cameFromTheSource(config: AppConfig): Boolean {
        val name = config.name.trim()
        val last = Urls.segments(config.source.url).lastOrNull()?.removeSuffix(".git")
        return name.isEmpty() ||
            name.equals(last, ignoreCase = true) ||
            name.equals(config.packageName, ignoreCase = true) ||
            name.equals(Urls.host(config.source.url), ignoreCase = true) ||
            name == config.source.url
    }
}
