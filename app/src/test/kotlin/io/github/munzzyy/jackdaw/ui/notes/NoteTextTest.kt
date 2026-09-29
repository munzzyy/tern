package io.github.munzzyy.jackdaw.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.munzzyy.jackdaw.engine.NoteSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
    private val colors = NoteColors(Color.Blue, Color.LightGray)

    @Test
    fun stylesLandOnTheRightRanges() {
        val spans = listOf(
            NoteSpan.Text("a "),
            NoteSpan.Bold(listOf(NoteSpan.Text("bold"))),
            NoteSpan.Text(" "),
            NoteSpan.Italic(listOf(NoteSpan.Text("it"))),
            NoteSpan.Code("x()"),
        )
        val s = annotate(spans, colors) {}
        assertEquals("a bold itx()", s.text)
        val bold = s.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(2 to 6, bold.start to bold.end)
        val italic = s.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(7 to 9, italic.start to italic.end)
    }

    @Test
    fun webLinksAreClickableAndReportTheirUrl() {
        var clicked: String? = null
        val s = annotate(listOf(NoteSpan.Link(listOf(NoteSpan.Text("guide")), "https://example.org/g")), colors) { clicked = it }
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals(0 to 5, link.start to link.end)
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals("https://example.org/g", clickable.tag)
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals("https://example.org/g", clicked)
    }

    @Test
    fun nonWebLinksBecomePlainText() {
        val s = annotate(listOf(NoteSpan.Link(listOf(NoteSpan.Text("run")), "javascript:alert(1)")), colors) {}
        assertEquals("run", s.text)
        assertTrue(s.getLinkAnnotations(0, s.length).isEmpty())
        assertTrue(isWebLink("HTTPS://example.org"))
    }

    @Test
    fun deepNestingIsFlattenedWithoutBlowingTheStack() {
        var span: NoteSpan = NoteSpan.Text("deep")
        repeat(100_000) { span = NoteSpan.Bold(listOf(span)) }
        val s = annotate(listOf(NoteSpan.Text("start "), span), colors) {}
        assertTrue(s.text.startsWith("start "))
    }

    @Test
    fun listMarkers() {
        assertEquals("3.", listMarker(true, 3, 0))
        assertEquals(listMarker(false, 0, 0), listMarker(false, 0, 3))
        assertEquals("•", listMarker(false, 0, -1))
    }
}
