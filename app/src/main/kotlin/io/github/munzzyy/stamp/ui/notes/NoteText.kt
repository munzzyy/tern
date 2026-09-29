package io.github.munzzyy.stamp.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import io.github.munzzyy.stamp.engine.NoteSpan

/** Nesting deeper than this is flattened to plain text so crafted notes cannot blow the stack. */
private const val MAX_DEPTH = 16

data class NoteColors(val link: Color, val codeBackground: Color)

fun isWebLink(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

fun annotate(spans: List<NoteSpan>, colors: NoteColors, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { appendSpans(spans, colors, onLink, 0) }

private fun AnnotatedString.Builder.appendSpans(
    spans: List<NoteSpan>,
    colors: NoteColors,
    onLink: (String) -> Unit,
    depth: Int,
) {
    for (span in spans) {
        if (depth >= MAX_DEPTH) {
            append(plainText(span, 0))
            continue
        }
        when (span) {
            is NoteSpan.Text -> append(span.text)
            is NoteSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendSpans(span.spans, colors, onLink, depth + 1)
            }
            is NoteSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendSpans(span.spans, colors, onLink, depth + 1)
            }
            is NoteSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                append(span.text)
            }
            is NoteSpan.Link -> if (isWebLink(span.url)) {
                val styles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                withLink(LinkAnnotation.Clickable(span.url, styles) { onLink(span.url) }) {
                    appendSpans(span.spans, colors, onLink, depth + 1)
                }
            } else {
                appendSpans(span.spans, colors, onLink, depth + 1)
            }
        }
    }
}

fun plainText(span: NoteSpan, depth: Int): String {
    if (depth >= MAX_DEPTH) return ""
    return when (span) {
        is NoteSpan.Text -> span.text
        is NoteSpan.Code -> span.text
        is NoteSpan.Bold -> span.spans.joinToString("") { plainText(it, depth + 1) }
        is NoteSpan.Italic -> span.spans.joinToString("") { plainText(it, depth + 1) }
        is NoteSpan.Link -> span.spans.joinToString("") { plainText(it, depth + 1) }
    }
}

private val BULLETS = listOf("•", "◦", "▪")

fun listMarker(ordered: Boolean, number: Int, depth: Int): String =
    if (ordered) "$number." else BULLETS[depth.coerceAtLeast(0) % BULLETS.size]
