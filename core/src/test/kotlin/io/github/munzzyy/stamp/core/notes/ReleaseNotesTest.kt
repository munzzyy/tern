package io.github.munzzyy.stamp.core.notes

import io.github.munzzyy.stamp.core.model.NotesFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun headingAndParagraph() {
        val blocks = ReleaseNotes.parse("# Title\n\nHello world.", NotesFormat.MARKDOWN)
        assertEquals(2, blocks.size)
        val heading = blocks[0] as Block.Heading
        assertEquals(1, heading.level)
        assertEquals("Title", (heading.spans[0] as Span.Text).text)
        val paragraph = blocks[1] as Block.Paragraph
        assertEquals("Hello world.", (paragraph.spans[0] as Span.Text).text)
    }

    @Test
    fun boldAndItalicNesting() {
        val blocks = ReleaseNotes.parse("**bold _and italic_**", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        val bold = paragraph.spans[0] as Span.Bold
        assertTrue(bold.spans.any { it is Span.Italic })
    }

    @Test
    fun codeSpanIsLiteral() {
        val blocks = ReleaseNotes.parse("Use `--force` carefully", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        assertTrue(paragraph.spans.any { it is Span.CodeSpan && it.text == "--force" })
    }

    @Test
    fun fencedCodeBlock() {
        val blocks = ReleaseNotes.parse("```\nline one\nline two\n```", NotesFormat.MARKDOWN)
        val code = blocks[0] as Block.Code
        assertEquals("line one\nline two", code.text)
    }

    @Test
    fun blockQuote() {
        val blocks = ReleaseNotes.parse("> quoted text", NotesFormat.MARKDOWN)
        val quote = blocks[0] as Block.Quote
        assertEquals("quoted text", (quote.spans[0] as Span.Text).text)
    }

    @Test
    fun horizontalRule() {
        val blocks = ReleaseNotes.parse("above\n\n---\n\nbelow", NotesFormat.MARKDOWN)
        assertTrue(blocks.any { it is Block.Rule })
    }

    @Test
    fun unorderedAndOrderedListsWithNesting() {
        val blocks = ReleaseNotes.parse("- top\n  - nested\n1. first\n2. second", NotesFormat.MARKDOWN)
        val top = blocks[0] as Block.ListItem
        assertEquals(false, top.ordered)
        val nested = blocks[1] as Block.ListItem
        assertTrue(nested.depth > top.depth)
        val ordered = blocks[2] as Block.ListItem
        assertEquals(1, ordered.number)
    }

    @Test
    fun linkKeepsHttpsUrl() {
        val blocks = ReleaseNotes.parse("[docs](https://example.com/docs)", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        val link = paragraph.spans[0] as Span.Link
        assertEquals("https://example.com/docs", link.url)
    }

    @Test
    fun javascriptLinkBecomesPlainText() {
        val blocks = ReleaseNotes.parse("[click me](javascript:alert(1))", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        assertTrue(paragraph.spans.none { it is Span.Link })
    }

    @Test
    fun bareUrlBecomesLink() {
        val blocks = ReleaseNotes.parse("See https://example.com/release for details", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        assertTrue(paragraph.spans.any { it is Span.Link })
    }

    @Test
    fun imageBecomesAltText() {
        val blocks = ReleaseNotes.parse("![a screenshot](https://example.com/shot.png)", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        assertEquals("a screenshot", (paragraph.spans[0] as Span.Text).text)
    }

    @Test
    fun githubMentionsAndIssuesStayPlainText() {
        val blocks = ReleaseNotes.parse("Thanks @octocat for fixing #123", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        assertTrue(paragraph.spans.none { it is Span.Link })
        val text = paragraph.spans.filterIsInstance<Span.Text>().joinToString("") { it.text }
        assertTrue(text.contains("@octocat"))
        assertTrue(text.contains("#123"))
    }

    @Test
    fun rawHtmlInsideMarkdownIsStrippedToText() {
        val blocks = ReleaseNotes.parse("Hello <b>world</b>!", NotesFormat.MARKDOWN)
        val paragraph = blocks[0] as Block.Paragraph
        val text = paragraph.spans.filterIsInstance<Span.Text>().joinToString("") { it.text }
        assertTrue(text.contains("world"))
        assertTrue(paragraph.spans.none { it is Span.Bold })
    }

    @Test
    fun deeplyNestedUnclosedBracketsDoNotHang() {
        val input = "[".repeat(100_000)
        val start = System.nanoTime()
        val blocks = ReleaseNotes.parse(input, NotesFormat.MARKDOWN)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 5000)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun deeplyNestedBoldMarkersDoNotOverflow() {
        val input = "*".repeat(50_000)
        val blocks = ReleaseNotes.parse(input, NotesFormat.MARKDOWN)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun massiveUnclosedHtmlTagsDoNotHang() {
        val input = "<".repeat(100_000)
        val start = System.nanoTime()
        val blocks = ReleaseNotes.parse(input, NotesFormat.HTML)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 5000)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun htmlHeadingsAndFormatting() {
        val blocks = ReleaseNotes.parse("<h2>Changes</h2><p>Fixed a <b>bug</b> in <i>parsing</i>.</p>", NotesFormat.HTML)
        val heading = blocks[0] as Block.Heading
        assertEquals(2, heading.level)
        val paragraph = blocks[1] as Block.Paragraph
        assertTrue(paragraph.spans.any { it is Span.Bold })
        assertTrue(paragraph.spans.any { it is Span.Italic })
    }

    @Test
    fun htmlListItems() {
        val blocks = ReleaseNotes.parse("<ul><li>one</li><li>two</li></ul>", NotesFormat.HTML)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Block.ListItem })
    }

    @Test
    fun htmlLinkFiltersNonHttpScheme() {
        val blocks = ReleaseNotes.parse("<a href=\"javascript:alert(1)\">click</a>", NotesFormat.HTML)
        val paragraph = blocks[0] as Block.Paragraph
        assertTrue(paragraph.spans.none { it is Span.Link })
    }

    @Test
    fun htmlPreBecomesCodeBlock() {
        val blocks = ReleaseNotes.parse("<pre>raw text</pre>", NotesFormat.HTML)
        val code = blocks[0] as Block.Code
        assertEquals("raw text", code.text)
    }

    @Test
    fun plainFormatSplitsOnBlankLines() {
        val blocks = ReleaseNotes.parse("first\n\nsecond", NotesFormat.PLAIN)
        assertEquals(2, blocks.size)
    }

    @Test
    fun neverThrowsOnEmptyInput() {
        assertEquals(emptyList<Block>(), ReleaseNotes.parse("", NotesFormat.MARKDOWN))
    }

    @Test
    fun capsOutputBlockCount() {
        val input = (1..5000).joinToString("\n\n") { "paragraph $it" }
        val blocks = ReleaseNotes.parse(input, NotesFormat.MARKDOWN)
        assertTrue(blocks.size <= 2000)
    }

    private fun everyText(blocks: List<Block>): List<String> {
        fun of(span: Span): List<String> = when (span) {
            is Span.Text -> listOf(span.text)
            is Span.Bold -> span.spans.flatMap(::of)
            is Span.Italic -> span.spans.flatMap(::of)
            is Span.CodeSpan -> listOf(span.text)
            is Span.Link -> span.spans.flatMap(::of) + span.url
        }
        return blocks.flatMap { block ->
            when (block) {
                is Block.Heading -> block.spans.flatMap(::of)
                is Block.Paragraph -> block.spans.flatMap(::of)
                is Block.ListItem -> block.spans.flatMap(::of)
                is Block.Quote -> block.spans.flatMap(::of)
                is Block.Code -> listOf(block.text)
                Block.Rule -> emptyList()
            }
        }
    }

    private val notDrawn = listOf("‪", "‫", "‬", "‭", "‮", "⁦", "⁧", "⁨", "⁩", "‎", "‏", "​", "﻿", "\u0000", "\u001B")

    @Test
    fun noFormatLetsAMarkThatTurnsTheDirectionOfWritingThrough() {
        val inputs = mapOf(
            NotesFormat.MARKDOWN to "# Ti%stle\n\nsha256: %sabcdef **bo%sld** `co%sde` [la%sbel](https://example.org/%spath)\n\n    indented %scode\n\n> quo%sted\n\n- it%sem",
            NotesFormat.HTML to "<h1>Ti%stle</h1><p>sha256: %sabcdef <b>bo%sld</b> <code>co%sde</code> <a href=\"https://example.org/%spath\">la%sbel</a></p><pre>pre%s</pre><ul><li>it%sem</li></ul>",
            NotesFormat.PLAIN to "first %sparagraph\n\nsecond %sparagraph",
        )
        for ((format, template) in inputs) {
            for (mark in notDrawn) {
                val texts = everyText(ReleaseNotes.parse(template.replace("%s", mark), format))
                assertTrue("$format gave nothing", texts.isNotEmpty())
                for (text in texts) assertTrue("$format let U+%04X through in: $text".format(mark[0].code), !text.contains(mark))
            }
        }
    }

    @Test
    fun theShapeOfTheNotesIsKeptWhenCharactersAreLeftOut() {
        val blocks = ReleaseNotes.parse("# Ti‮tle\n\nHello​ world.\n\n- one\n- two", NotesFormat.MARKDOWN)
        assertEquals(4, blocks.size)
        assertEquals("Title", ((blocks[0] as Block.Heading).spans[0] as Span.Text).text)
        assertEquals("Hello world.", ((blocks[1] as Block.Paragraph).spans[0] as Span.Text).text)
        assertTrue(blocks[2] is Block.ListItem && blocks[3] is Block.ListItem)
    }

    @Test
    fun anAddressIsJudgedAfterTheCharactersAreLeftOut() {
        val script = ReleaseNotes.parse("[click](java\u0000script:alert)", NotesFormat.MARKDOWN)
        assertTrue((script[0] as Block.Paragraph).spans.none { it is Span.Link })
        val html = ReleaseNotes.parse("<a href=\"‮javascript:alert\">click</a>", NotesFormat.HTML)
        assertTrue((html[0] as Block.Paragraph).spans.none { it is Span.Link })
        val web = ReleaseNotes.parse("[site](ht​tps://example.org/a‮b)", NotesFormat.MARKDOWN)
        val link = (web[0] as Block.Paragraph).spans.single() as Span.Link
        assertEquals("https://example.org/ab", link.url)
    }

    @Test
    fun theJoinersThatSomeScriptsAreWrittenWithStay() {
        val persian = "می‌خواهم"
        val blocks = ReleaseNotes.parse(persian, NotesFormat.MARKDOWN)
        assertEquals(persian, ((blocks[0] as Block.Paragraph).spans[0] as Span.Text).text)
    }
}
