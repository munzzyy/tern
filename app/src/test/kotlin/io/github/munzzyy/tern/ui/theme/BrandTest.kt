package io.github.munzzyy.tern.ui.theme

import io.github.munzzyy.tern.engine.Contrast
import io.github.munzzyy.tern.engine.Palette
import io.github.munzzyy.tern.ui.icons.SealShape
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colours and the drawing in the resources are copies. This holds them to what they are copies of. */
class BrandTest {
    private val res = File("src/main/res")
    private val ink = Palette.INK.seed

    private fun colors(folder: String): Map<String, Int> =
        Regex("""<color name="(\w+)">#([0-9A-Fa-f]{8})</color>""")
            .findAll(File(res, "$folder/colors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }

    private fun hex(argb: Int?): String = argb?.let { "#%08X".format(it) } ?: "missing"

    private fun number(value: Float): String = "%.2f".format(value).trimEnd('0').trimEnd('.')

    @Test
    fun theWindowOpensInTheSurfaceOfTheInkScheme() {
        val light = roles(ink.hue, ink.strength, dark = false, Contrast.STANDARD)[Role.SURFACE]
        val dark = roles(ink.hue, ink.strength, dark = true, Contrast.STANDARD)[Role.SURFACE]
        assertEquals(hex(light), hex(colors("values")["window_background"]))
        assertEquals(hex(dark), hex(colors("values-night")["window_background"]))
    }

    @Test
    fun theIconIsPaperOnInk() {
        val colors = colors("values")
        val ground = tableColor(Role.PRIMARY, ink.hue, ink.strength, dark = false, Contrast.MEDIUM)
        val paper = tableColor(Role.SURFACE, ink.hue, ink.strength, dark = false, Contrast.STANDARD)
        assertEquals(hex(ground), hex(colors["icon_background"]))
        assertEquals(hex(paper), hex(colors["icon_seal"]))
        assertTrue(wcag(ground, paper) >= 7.0)
        assertEquals(setOf("window_background", "icon_background", "icon_seal"), colors.keys)
    }

    @Test
    fun theLauncherIconIsTheSealTheScreensDraw() {
        val outer = 28f
        val centre = 54f
        val check = SealShape.CHECK.joinToString(" L") { (x, y) -> "${number(centre + x * outer)},${number(centre + y * outer)}" }
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val xml = File(res, "drawable/$name.xml").readText()
            assertTrue("$name: check", xml.contains("android:pathData=\"M$check\""))
            assertTrue("$name: check width", xml.contains("android:strokeWidth=\"${number(SealShape.CHECK_WIDTH * outer)}\""))
            assertTrue("$name: ring", xml.contains("a${number(SealShape.RING * outer)},${number(SealShape.RING * outer)} "))
            assertTrue("$name: ring width", xml.contains("android:strokeWidth=\"${number(SealShape.RING_WIDTH * outer)}\""))
            assertTrue("$name: inner ring", xml.contains("a${number(SealShape.INNER_RING * outer)},${number(SealShape.INNER_RING * outer)} "))
            assertTrue("$name: tilt", xml.contains("android:rotation=\"${number(SealShape.TILT)}\""))
            assertTrue("$name: the seal stays inside what every launcher shows", outer * (SealShape.RING + SealShape.RING_WIDTH / 2) <= 33f)
        }
        val adaptive = File(res, "mipmap-anydpi/ic_launcher.xml").readText()
        for (layer in listOf("background", "foreground", "monochrome")) assertTrue(layer, adaptive.contains("<$layer android:drawable="))
        assertFalse("the old banner is gone", File(res, "drawable/banner.xml").exists())
    }

    @Test
    fun theBannerIsTheSealAndTheNameOnInk() {
        val banner = ImageIO.read(File(res, "drawable-xhdpi/banner.png"))
        assertEquals(640, banner.width)
        assertEquals(360, banner.height)
        val colors = colors("values")
        val ground = colors.getValue("icon_background")
        val paper = colors.getValue("icon_seal")
        for ((x, y) in listOf(0 to 0, 639 to 0, 0 to 359, 639 to 359, 320 to 8, 320 to 352)) {
            assertEquals("at $x, $y", hex(ground), hex(banner.getRGB(x, y)))
        }
        var drawn = 0
        var left = banner.width
        var right = 0
        var top = banner.height
        var bottom = 0
        for (y in 0 until banner.height) {
            for (x in 0 until banner.width) {
                val pixel = banner.getRGB(x, y)
                if (pixel == ground) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                if (pixel == paper) drawn++
            }
        }
        assertTrue("seal and name are drawn in paper: $drawn pixels", drawn > 8000)
        assertTrue("clear of the sides: $left to $right", left >= 48 && right <= 640 - 48)
        assertTrue("clear of top and bottom: $top to $bottom", top >= 54 && bottom <= 360 - 54)
        assertTrue("the seal is on the left and the name runs to the right: $left to $right", left < 160 && right > 480)
    }
}
