package io.github.munzzyy.tern.engine

/** Release notes as the screens draw them. Links are always http or https. */
sealed interface NoteBlock {
    data class Heading(val level: Int, val spans: List<NoteSpan>) : NoteBlock

    data class Paragraph(val spans: List<NoteSpan>) : NoteBlock

    data class ListItem(val depth: Int, val ordered: Boolean, val number: Int, val spans: List<NoteSpan>) : NoteBlock

    data class Code(val text: String) : NoteBlock

    data class Quote(val spans: List<NoteSpan>) : NoteBlock

    data object Rule : NoteBlock
}

sealed interface NoteSpan {
    data class Text(val text: String) : NoteSpan

    data class Bold(val spans: List<NoteSpan>) : NoteSpan

    data class Italic(val spans: List<NoteSpan>) : NoteSpan

    data class Code(val text: String) : NoteSpan

    data class Link(val spans: List<NoteSpan>, val url: String) : NoteSpan
}
