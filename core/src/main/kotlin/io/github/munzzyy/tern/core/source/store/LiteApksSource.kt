package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceTypes

/** LiteAPKs. Not read yet: it recognises no address, so detection passes it by. */
class LiteApksSource : Source {
    override val type: String = SourceTypes.LITEAPKS

    override val republishes: Boolean get() = true

    override fun match(url: String): SourceSpec? = null

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult =
        throw SourceException(SourceErrorKind.UNSUPPORTED, "LiteAPKs is not read yet")
}
