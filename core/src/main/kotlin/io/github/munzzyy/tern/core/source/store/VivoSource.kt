package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceTypes

/** vivo App Store. Not read yet: it recognises no address, so detection passes it by. */
class VivoSource : Source {
    override val type: String = SourceTypes.VIVO

    override fun match(url: String): SourceSpec? = null

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult =
        throw SourceException(SourceErrorKind.UNSUPPORTED, "vivo App Store is not read yet")
}
