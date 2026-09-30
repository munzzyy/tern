package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions

/**
 * What tells a file from the one before it when neither the page nor the address names a version,
 * stored in [SourceOptions.PSEUDO] as [option]. It becomes the release id; the version is read
 * from the file once it is fetched. Without one, the server's ETag counts, else its Last-Modified,
 * else the file's size.
 */
enum class PseudoVersion(val option: String) {
    /** A hash of the file's first bytes, asked for with a Range request. */
    HASH("hash"),

    /** A hash of the address: the file is not asked for, and a page whose links did not change is not read again. */
    LINK("link"),

    /** The server's ETag, and nothing else. */
    ETAG("etag"),
    ;

    companion object {
        /** The method [spec] asks for, or null for the default. */
        @Throws(SourceException::class)
        fun of(spec: SourceSpec): PseudoVersion? {
            val raw = spec.option(SourceOptions.PSEUDO) ?: return null
            return entries.firstOrNull { it.option == raw }
                ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.PSEUDO} must be one of ${entries.joinToString { it.option }}")
        }
    }
}
