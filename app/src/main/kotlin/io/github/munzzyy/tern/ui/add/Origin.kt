package io.github.munzzyy.tern.ui.add

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.ui.common.TrustLine
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.text.hostOf

/** What Tern says of where an app's files come from, for the stores that are not the developer's own place. */
enum class OriginNote { REPUBLISHED, STORE }

fun originNote(type: String): OriginNote? = when (type) {
    in SourceTypes.REPUBLISHING -> OriginNote.REPUBLISHED
    in SourceTypes.THIRD_PARTY_STORES -> OriginNote.STORE
    else -> null
}

/**
 * [pinned] when the app is held to a certificate before its first install: one Tern carries, one
 * that came with a link or a file, or the one of the app already on the device. Otherwise the
 * first file from the store decides it, and the note says so.
 */
@StringRes
fun originText(note: OriginNote, pinned: Boolean): Int = when (note) {
    OriginNote.REPUBLISHED -> if (pinned) R.string.origin_republished_pinned else R.string.origin_republished_first
    OriginNote.STORE -> if (pinned) R.string.origin_store_pinned else R.string.origin_store_first
}

/** What the preview will hold the app to once it is added. */
fun previewPinned(found: Detection.Found): Boolean =
    found.builtInPin || found.carried?.pinnedSigners?.isNotEmpty() == true || found.installed != null

/** The store's own name, or its host where it has none. */
fun sourceName(spec: SourceSpec): String = SourceTypes.displayName(spec.type) ?: hostOf(spec.url)

/** Says where the files of an app from a third-party store come from, and what its first install decides; nothing for any other source. */
@Composable
fun OriginLine(spec: SourceSpec, pinned: Boolean) {
    val note = originNote(spec.type) ?: return
    TrustLine(Trust.NOTE, stringResource(originText(note, pinned), sourceName(spec)))
}
