package io.github.munzzyy.jackdaw.fake

import io.github.munzzyy.jackdaw.engine.QrCode
import io.github.munzzyy.jackdaw.engine.Suggestion
import io.github.munzzyy.jackdaw.engine.SuggestionKind

/** Invented apps, so that no screenshot or test of the stand-in names a real project. */
object FakeSuggestions {
    private fun s(name: String, summary: String, kind: SuggestionKind, tv: Boolean = false) =
        Suggestion(name, summary, FakeLinks.SUGGESTED_PREFIX + name.lowercase().filter { it.isLetterOrDigit() }, kind, tv)

    val all: List<Suggestion> = listOf(
        s("Lantern Player", "Plays video from your own shelves", SuggestionKind.MEDIA, tv = true),
        s("Couch Tube", "Video sites on the big screen, by remote", SuggestionKind.MEDIA, tv = true),
        s("Hearth Launcher", "A quiet home screen for a television", SuggestionKind.LAUNCHERS, tv = true),
        s("Stream Bridge", "Games from your computer on the television", SuggestionKind.GAMES, tv = true),
        s("Pocket Notes", "Notes in plain files", SuggestionKind.TOOLS),
        s("Trail Atlas", "Maps that work without a connection", SuggestionKind.MAPS),
        s("Quiet Keys", "A keyboard that sends nothing anywhere", SuggestionKind.PRIVACY),
        s("Paper Boat", "A reader for feeds and long articles", SuggestionKind.READING),
        s("Ridge Radio", "Radio stations from around the world", SuggestionKind.MEDIA),
        s("Kestrel Mail", "Mail for any provider", SuggestionKind.MESSAGING),
        s("Vault Keys", "Passwords kept on the device", SuggestionKind.PRIVACY),
        s("Tide Table", "A calendar that stays on the device", SuggestionKind.TOOLS),
    )

    /** Looks like a QR code from across the room and reads as nothing, which is all a layout needs. */
    fun pattern(size: Int = 33): QrCode {
        val dark = BooleanArray(size * size)
        fun finder(left: Int, top: Int) {
            for (y in 0 until 7) for (x in 0 until 7) {
                val edge = x == 0 || x == 6 || y == 0 || y == 6
                val core = x in 2..4 && y in 2..4
                dark[(top + y) * size + left + x] = edge || core
            }
        }
        var seed = 0x2545F491
        for (i in dark.indices) {
            seed = seed * 1103515245 + 12345
            dark[i] = (seed ushr 16) and 1 == 1
        }
        for (y in 0 until 8) for (x in 0 until 8) {
            dark[y * size + x] = false
            dark[y * size + size - 1 - x] = false
            dark[(size - 1 - y) * size + x] = false
        }
        finder(0, 0)
        finder(size - 7, 0)
        finder(0, size - 7)
        return QrCode(size, dark)
    }
}
