package io.github.munzzyy.stamp.ui.theme

import io.github.munzzyy.stamp.engine.Contrast
import io.github.munzzyy.stamp.engine.Palette
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2's own formula, written out here so the engine is not measured with its own ruler. */
private fun wcagChannel(value: Int): Double {
    val c = value / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun wcagLuminance(argb: Int): Double =
    0.2126 * wcagChannel(argb shr 16 and 0xFF) + 0.7152 * wcagChannel(argb shr 8 and 0xFF) + 0.0722 * wcagChannel(argb and 0xFF)

fun wcag(a: Int, b: Int): Double {
    val first = wcagLuminance(a)
    val second = wcagLuminance(b)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun least(role: Role, contrast: Contrast): Double = when {
    role == Role.OUTLINE -> if (contrast == Contrast.HIGH) 4.5 else 3.0
    contrast == Contrast.HIGH -> 7.0
    else -> 4.5
}

class ContrastTest {
    private val seeds: List<Pair<String, Seed>> =
        Palette.entries.map { it.name to it.seed } + (0 until 360 step 10).map { "own hue $it" to Seed(it, CUSTOM_STRENGTH) }

    private fun tooFaint(roles: Roles, contrast: Contrast, what: String): List<String> = buildList {
        for (role in Role.entries) {
            for (ground in role.readOn) {
                val ratio = wcag(roles[role], roles[ground])
                if (ratio < least(role, contrast)) {
                    add("$what: $role ${hex(roles[role])} on $ground ${hex(roles[ground])} is %.2f to 1".format(ratio))
                }
            }
        }
    }

    @Test
    fun everyColourThatIsReadReachesItsContrastOnEveryBackground() {
        val faint = mutableListOf<String>()
        var schemes = 0
        for ((name, seed) in seeds) {
            for (dark in listOf(false, true)) {
                for (black in listOf(false, true)) {
                    for (contrast in Contrast.entries) {
                        val roles = roles(seed.hue, seed.strength, dark, contrast, black)
                        faint += tooFaint(roles, contrast, "$name, ${if (dark) "dark" else "light"}, black $black, $contrast")
                        schemes++
                    }
                }
            }
        }
        assertEquals(44 * 2 * 2 * 3, schemes)
        assertTrue("${faint.size} pairs are too faint, the first of them:\n" + faint.take(12).joinToString("\n"), faint.isEmpty())
    }

    @Test
    fun theTableNamesWhatEveryOnColourIsReadOn() {
        val read = Role.entries.filter { it.readOn.isNotEmpty() }
        for (role in Role.entries) {
            if (role.name.startsWith("ON_") || role.name.startsWith("INVERSE_ON_")) assertTrue("$role is read on nothing", role in read)
        }
        for (accent in listOf(Role.PRIMARY, Role.SECONDARY, Role.TERTIARY, Role.ERROR, Role.VERIFIED, Role.CAUTION)) {
            assertTrue("$accent is not tested on the surfaces", Role.SURFACE_CONTAINER_HIGHEST in accent.readOn && Role.SURFACE in accent.readOn)
        }
    }

    @Test
    fun aWashedOutSchemeIsPulledApart() {
        for (dark in listOf(false, true)) {
            val good = roles(277, 1.0, dark, Contrast.STANDARD)
            val washed = good.changed { argb ->
                for (role in Role.entries) {
                    val from = oklchOf(argb[role.ordinal])
                    argb[role.ordinal] = toned(from.hue, from.chroma, 50 + (toneOf(argb[role.ordinal]) - 50) * 0.55)
                }
            }
            assertTrue("the washed out scheme has to fail, or this test proves nothing", tooFaint(washed, Contrast.STANDARD, "washed").isNotEmpty())
            for (contrast in Contrast.entries) {
                val faint = tooFaint(washed.fitted(contrast), contrast, "washed out, ${if (dark) "dark" else "light"}, $contrast")
                assertTrue(faint.joinToString("\n"), faint.isEmpty())
            }
        }
    }

    @Test
    fun measuringBarelyMovesWhatTheTableOfTonesGives() {
        for ((name, seed) in seeds) {
            for (dark in listOf(false, true)) {
                for (black in listOf(false, true)) {
                    for (contrast in Contrast.entries) {
                        val table = drawn(seed.hue, seed.strength, dark, contrast, black)
                        val measured = table.fitted(contrast)
                        for (role in Role.entries) {
                            val moved = abs(toneOf(table[role]) - toneOf(measured[role]))
                            assertTrue("$name, dark $dark, black $black, $contrast: $role moved by %.1f tones".format(moved), moved <= 2.0)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun pureBlackMakesADarkSchemeBlackAndLeavesALightOneAlone() {
        for ((_, seed) in seeds) {
            val black = roles(seed.hue, seed.strength, dark = true, Contrast.STANDARD, pureBlack = true)
            assertEquals(BLACK, black[Role.BACKGROUND])
            assertEquals(BLACK, black[Role.SURFACE])
            assertTrue(toneOf(black[Role.SURFACE_CONTAINER_HIGHEST]) > toneOf(black[Role.SURFACE_CONTAINER]))
            assertTrue(toneOf(black[Role.SURFACE_CONTAINER]) > 0)
            assertEquals(
                roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD),
                roles(seed.hue, seed.strength, dark = false, Contrast.STANDARD, pureBlack = true),
            )
        }
    }

    @Test
    fun theAccentKeepsTheHueThatWasAskedFor() {
        for ((name, seed) in seeds) {
            for (dark in listOf(false, true)) {
                val primary = oklchOf(roles(seed.hue, seed.strength, dark, Contrast.STANDARD)[Role.PRIMARY])
                val off = abs(((primary.hue - seed.hue + 540) % 360) - 180)
                assertTrue("$name: asked for ${seed.hue}, got %.1f".format(primary.hue), off < 4)
            }
        }
    }

    @Test
    fun meaningKeepsItsHueWhateverTheAccentIs() {
        val hues = (0 until 360 step 30).map { roles(it, CUSTOM_STRENGTH, dark = false, Contrast.STANDARD) }
        for (role in listOf(Role.VERIFIED, Role.VERIFIED_CONTAINER, Role.CAUTION, Role.CAUTION_CONTAINER, Role.ERROR, Role.ERROR_CONTAINER)) {
            assertEquals("$role changes with the accent", 1, hues.map { it[role] }.distinct().size)
        }
        val any = hues.first()
        assertTrue("verified is green", oklchOf(any[Role.VERIFIED]).hue in 135.0..165.0)
        assertTrue("caution is amber", oklchOf(any[Role.CAUTION]).hue in 60.0..95.0)
        assertTrue("refused is red", oklchOf(any[Role.ERROR]).hue in 15.0..40.0)
    }

    @Test
    fun strengthIsHowMuchColourThereIs() {
        val faint = oklchOf(roles(277, 0.1, dark = false, Contrast.STANDARD)[Role.PRIMARY]).chroma
        val strong = oklchOf(roles(277, 1.0, dark = false, Contrast.STANDARD)[Role.PRIMARY]).chroma
        assertTrue("$faint against $strong", strong > faint * 2)
    }

    @Test
    fun moreContrastMovesTextFurtherFromItsBackground() {
        for ((name, seed) in seeds) {
            for (dark in listOf(false, true)) {
                val levels = Contrast.entries.map { roles(seed.hue, seed.strength, dark, it) }
                for (role in listOf(Role.PRIMARY, Role.ON_SURFACE_VARIANT, Role.ON_PRIMARY_CONTAINER, Role.OUTLINE)) {
                    val ratios = levels.map { scheme -> role.readOn.minOf { wcag(scheme[role], scheme[it]) } }
                    assertTrue("$name, dark $dark, $role: $ratios", ratios[0] < ratios[1] && ratios[1] < ratios[2])
                }
            }
        }
    }
}
