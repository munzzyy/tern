package io.github.munzzyy.tern.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

private val search: ImageVector by lazy {
    glyph("Search") {
        line {
            circle(10.5f, 10.5f, 6.5f)
            moveTo(15.5f, 15.5f)
            lineTo(20.5f, 20.5f)
        }
    }
}

private val close: ImageVector by lazy {
    glyph("Close") {
        line {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }
}

private val more: ImageVector by lazy {
    glyph("More") {
        solid {
            for (y in listOf(5.5f, 12f, 18.5f)) circle(12f, y, 1.7f)
        }
    }
}

private val info: ImageVector by lazy {
    glyph("Info") {
        line {
            circle(12f, 12f, 9f)
            moveTo(12f, 11f)
            verticalLineTo(16.5f)
            moveTo(12f, 7.5f)
            verticalLineTo(7.6f)
        }
    }
}

private val bin: ImageVector by lazy {
    glyph("Bin") {
        line {
            moveTo(4f, 7f)
            horizontalLineTo(20f)
            moveTo(6.5f, 7f)
            lineTo(7.5f, 20f)
            horizontalLineTo(16.5f)
            lineTo(17.5f, 7f)
            moveTo(9.5f, 7f)
            verticalLineTo(4f)
            horizontalLineTo(14.5f)
            verticalLineTo(7f)
        }
    }
}

private val expand: ImageVector by lazy {
    glyph("Expand") {
        line {
            moveTo(6f, 9.5f)
            lineTo(12f, 15.5f)
            lineTo(18f, 9.5f)
        }
    }
}

private val collapse: ImageVector by lazy {
    glyph("Collapse") {
        line {
            moveTo(6f, 14.5f)
            lineTo(12f, 8.5f)
            lineTo(18f, 14.5f)
        }
    }
}

private val bell: ImageVector by lazy {
    glyph("Bell") {
        line {
            moveTo(6f, 17f)
            verticalLineTo(11f)
            arcTo(6f, 6f, 0f, false, true, 18f, 11f)
            verticalLineTo(17f)
            moveTo(4f, 17f)
            horizontalLineTo(20f)
            moveTo(10.5f, 20.5f)
            horizontalLineTo(13.5f)
        }
    }
}

private val minus: ImageVector by lazy {
    glyph("Minus") {
        line {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }
}

private val plus: ImageVector by lazy {
    glyph("Plus") {
        line {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }
}

val Glyphs.Search: ImageVector get() = search
val Glyphs.Close: ImageVector get() = close
val Glyphs.More: ImageVector get() = more
val Glyphs.Info: ImageVector get() = info
val Glyphs.Bin: ImageVector get() = bin
val Glyphs.Expand: ImageVector get() = expand
val Glyphs.Collapse: ImageVector get() = collapse
val Glyphs.Bell: ImageVector get() = bell
val Glyphs.Plus: ImageVector get() = plus
val Glyphs.Minus: ImageVector get() = minus
