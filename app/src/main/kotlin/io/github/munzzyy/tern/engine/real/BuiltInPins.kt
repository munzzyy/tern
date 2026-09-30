package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.suggest.Catalog
import io.github.munzzyy.tern.core.suggest.SuggestedApp

/**
 * The certificates Tern itself carries, by the address of the app, and by its package for an app
 * that comes from somewhere else. They come from the starter list, which ships inside Tern, so they
 * are known before the first file is.
 */
class BuiltInPins(private val catalog: List<SuggestedApp> = Catalog.all) {
    /** The certificates carried for the app at [url]. Empty for every other address. */
    fun of(url: String): List<String> = Catalog.entryAt(url, catalog)?.signers.orEmpty()

    /** Every certificate carried for an entry that ships [packageName], which may come from several channels. */
    fun ofPackage(packageName: String?): List<String> {
        if (packageName.isNullOrEmpty()) return emptyList()
        return catalog.filter { it.packageName == packageName }.flatMap { it.signers }.map { it.lowercase() }.distinct()
    }

    /**
     * What an app of [spec] that ships [packageName] is held to before its first install: the
     * certificates carried for its address, else, from a store, a mirror or a web page, those
     * carried for its package. A forge is left to its address, where a fork may keep the
     * package, and so is an F-Droid repository, whose signed index names the signer.
     */
    fun forApp(spec: SourceSpec, packageName: String?): List<String> =
        of(spec.url).ifEmpty { if (spec.type in BY_PACKAGE) ofPackage(packageName) else emptyList() }

    /** [forApp] where it names any certificate, whatever came with a link or a file, and [otherwise] everywhere else. */
    fun orElse(spec: SourceSpec, packageName: String?, otherwise: List<String>): List<String> = forApp(spec, packageName).ifEmpty { otherwise }

    /** True when [config] is held to certificates that are all among those carried for it. */
    fun hold(config: AppConfig): Boolean {
        val carried = forApp(config.source, config.packageName)
        return carried.isNotEmpty() && config.pinnedSigners.isNotEmpty() && config.pinnedSigners.all { pin -> carried.any { it.equals(pin, ignoreCase = true) } }
    }

    private companion object {
        val BY_PACKAGE: Set<String> = SourceTypes.THIRD_PARTY_STORES + setOf(SourceTypes.HTML, SourceTypes.DIRECT)
    }
}
