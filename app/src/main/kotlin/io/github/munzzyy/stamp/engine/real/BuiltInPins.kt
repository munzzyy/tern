package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.suggest.Catalog
import io.github.munzzyy.stamp.core.suggest.SuggestedApp

/**
 * The certificates Stamp itself carries, by the address of the app. They come from the starter
 * list, which ships inside Stamp, so they are known before the first file is.
 */
class BuiltInPins(private val catalog: List<SuggestedApp> = Catalog.all) {
    /** The certificates carried for the app at [url]. Empty for every other address. */
    fun of(url: String): List<String> = Catalog.entryAt(url, catalog)?.signers.orEmpty()

    /**
     * What an app at [url] is held to: the certificates carried for it where there are any,
     * whatever came with a link or a file, and [otherwise] everywhere else.
     */
    fun orElse(url: String, otherwise: List<String>): List<String> = of(url).ifEmpty { otherwise }

    /** True when [config] is held to certificates that are all among those carried for its address. */
    fun hold(config: AppConfig): Boolean {
        val carried = of(config.source.url)
        return carried.isNotEmpty() && config.pinnedSigners.isNotEmpty() && config.pinnedSigners.all { pin -> carried.any { it.equals(pin, ignoreCase = true) } }
    }
}
