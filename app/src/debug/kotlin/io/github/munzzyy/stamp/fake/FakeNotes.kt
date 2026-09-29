package io.github.munzzyy.stamp.fake

import io.github.munzzyy.stamp.engine.NoteBlock
import io.github.munzzyy.stamp.engine.NoteSpan

private fun t(s: String) = NoteSpan.Text(s)

fun shortNotes(version: String): List<NoteBlock> = listOf(
    NoteBlock.Paragraph(listOf(t("${if (version.isBlank()) "This release" else "Version $version"} fixes a crash when the list is empty and makes startup "), NoteSpan.Bold(listOf(t("faster"))), t(" on older phones."))),
    NoteBlock.ListItem(0, false, 0, listOf(t("Smaller download"))),
    NoteBlock.ListItem(0, false, 0, listOf(t("Clearer error messages"))),
)

fun longNotes(version: String): List<NoteBlock> = listOf(
    NoteBlock.Heading(1, listOf(t("Harbor Terminal $version"))),
    NoteBlock.Paragraph(
        listOf(
            t("This release reworks the session engine. Read the "),
            NoteSpan.Link(listOf(t("upgrade guide")), "https://downloads.example.org/harborterm/docs/upgrade-$version"),
            t(" before updating if you use "),
            NoteSpan.Code("~/.harborrc"),
            t(" with custom key bindings. "),
            NoteSpan.Italic(listOf(t("Nothing is lost"))),
            t(", but two options were renamed."),
        ),
    ),
    NoteBlock.Heading(2, listOf(t("New"))),
    NoteBlock.ListItem(0, false, 0, listOf(NoteSpan.Bold(listOf(t("Split panes"))), t(": drag the divider or use the keyboard."))),
    NoteBlock.ListItem(1, false, 0, listOf(t("Horizontal and vertical splits"))),
    NoteBlock.ListItem(1, false, 0, listOf(t("Each pane keeps its own history"))),
    NoteBlock.ListItem(2, false, 0, listOf(t("Up to 10,000 lines per pane by default"))),
    NoteBlock.ListItem(0, false, 0, listOf(t("A search bar that understands "), NoteSpan.Code("regex"), t(" when you ask it to"))),
    NoteBlock.ListItem(0, false, 0, listOf(t("Hardware keyboard shortcuts for every menu action"))),
    NoteBlock.Heading(2, listOf(t("Upgrading"))),
    NoteBlock.ListItem(0, true, 1, listOf(t("Back up your settings from the menu."))),
    NoteBlock.ListItem(0, true, 2, listOf(t("Install this version."))),
    NoteBlock.ListItem(1, true, 1, listOf(t("If asked, allow the new storage permission."))),
    NoteBlock.ListItem(0, true, 3, listOf(t("Rename the options below in "), NoteSpan.Code("~/.harborrc"), t("."))),
    NoteBlock.Code("# before\nbell_style = visual\nscrollback_lines = 5000\n\n# after\nbell = visual\nscrollback = 5000   # a very long trailing comment that should scroll sideways rather than wrap in the code block"),
    NoteBlock.Quote(listOf(t("Sessions started before the update keep running with the old engine until you close them."))),
    NoteBlock.Rule,
    NoteBlock.Heading(3, listOf(t("Fixed"))),
    NoteBlock.Paragraph(
        List(6) { i ->
            t("Issue ${i + 1}: the cursor no longer jumps after pasting multi-line text, colours survive a theme change, and long lines wrap at the pane edge instead of the screen edge. ")
        },
    ),
    NoteBlock.Paragraph(listOf(t("Full list of changes: "), NoteSpan.Link(listOf(t("https://downloads.example.org/harborterm/changes/$version")), "https://downloads.example.org/harborterm/changes/$version"))),
)
