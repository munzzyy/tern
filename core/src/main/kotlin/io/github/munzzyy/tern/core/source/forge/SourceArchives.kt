package io.github.munzzyy.tern.core.source.forge

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.net.Urls

/** The archives of a project's source that a forge makes for each release, as files a person can save. */
internal object SourceArchives {
    /**
     * [tarball] and [zipball] of the release [tag], named after [repo] as the forge's own page
     * names them. An address that is not HTTPS, or that [allowed] refuses, is left out.
     */
    fun of(repo: String, tag: String, tarball: String?, zipball: String?, needsAuth: Boolean, allowed: (String) -> Boolean): List<Asset> {
        val stem = "$repo-${tag.replace('/', '-')}"
        return listOfNotNull(
            tarball?.let { archive("$stem.tar.gz", it, needsAuth, allowed) },
            zipball?.let { archive("$stem.zip", it, needsAuth, allowed) },
        )
    }

    /** One archive at [url] as the file [name], or null where [url] is not one to fetch. */
    fun archive(name: String, url: String, needsAuth: Boolean, allowed: (String) -> Boolean): Asset? {
        if (!Urls.isHttps(url) || Urls.normalize(url) == null || !allowed(url)) return null
        return Asset(name = name, url = url, needsAuth = needsAuth)
    }
}
