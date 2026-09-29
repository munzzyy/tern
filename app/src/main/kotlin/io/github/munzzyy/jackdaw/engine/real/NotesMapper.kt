package io.github.munzzyy.jackdaw.engine.real

import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.notes.Block
import io.github.munzzyy.jackdaw.core.notes.ReleaseNotes
import io.github.munzzyy.jackdaw.core.notes.Span
import io.github.munzzyy.jackdaw.engine.NoteBlock
import io.github.munzzyy.jackdaw.engine.NoteSpan

object NotesMapper {
    fun map(release: Release): List<NoteBlock> {
        val text = release.notes?.takeIf { it.isNotBlank() } ?: return emptyList()
        return ReleaseNotes.parse(text, release.notesFormat).map(::block)
    }

    private fun block(b: Block): NoteBlock = when (b) {
        is Block.Heading -> NoteBlock.Heading(b.level, spans(b.spans))
        is Block.Paragraph -> NoteBlock.Paragraph(spans(b.spans))
        is Block.ListItem -> NoteBlock.ListItem(b.depth, b.ordered, b.number ?: 0, spans(b.spans))
        is Block.Code -> NoteBlock.Code(b.text)
        is Block.Quote -> NoteBlock.Quote(spans(b.spans))
        Block.Rule -> NoteBlock.Rule
    }

    private fun spans(list: List<Span>): List<NoteSpan> = list.map(::span)

    private fun span(s: Span): NoteSpan = when (s) {
        is Span.Text -> NoteSpan.Text(s.text)
        is Span.Bold -> NoteSpan.Bold(spans(s.spans))
        is Span.Italic -> NoteSpan.Italic(spans(s.spans))
        is Span.CodeSpan -> NoteSpan.Code(s.text)
        is Span.Link -> if (isWeb(s.url)) NoteSpan.Link(spans(s.spans), s.url) else NoteSpan.Text(s.spans.joinToString("") { plain(it) })
    }

    private fun isWeb(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private fun plain(s: Span): String = when (s) {
        is Span.Text -> s.text
        is Span.Bold -> s.spans.joinToString("") { plain(it) }
        is Span.Italic -> s.spans.joinToString("") { plain(it) }
        is Span.CodeSpan -> s.text
        is Span.Link -> s.spans.joinToString("") { plain(it) }
    }
}
