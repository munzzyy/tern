package io.github.munzzyy.tern.core.interop

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** What Obtainium's per-app options and Tern's mean alike, shared by the import and the export. */
internal object ObtainiumOptions {
    /**
     * The group of [versionExtract] Tern takes when none is named: the first one when the pattern
     * has a group, else the whole match. Obtainium takes the whole match unless told otherwise.
     */
    fun defaultGroup(versionExtract: String): String = if (groups(versionExtract) > 0) "1" else "0"

    private fun groups(pattern: String): Int = try {
        Pattern.compile(pattern).matcher("").groupCount()
    } catch (_: PatternSyntaxException) {
        0
    }
}
