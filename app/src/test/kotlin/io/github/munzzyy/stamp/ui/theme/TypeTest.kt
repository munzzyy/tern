package io.github.munzzyy.stamp.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeTest {
    private val phone = typographyFor(television = false)
    private val television = typographyFor(television = true)

    private fun styles(of: androidx.compose.material3.Typography) = listOf(
        of.displayLarge, of.displayMedium, of.displaySmall, of.headlineLarge, of.headlineMedium, of.headlineSmall,
        of.titleLarge, of.titleMedium, of.titleSmall, of.bodyLarge, of.bodyMedium, of.bodySmall,
        of.labelLarge, of.labelMedium, of.labelSmall,
    )

    @Test
    fun aPhoneHasMaterialsSizes() {
        assertEquals(listOf(57, 45, 36, 32, 28, 24, 22, 16, 14, 16, 14, 12, 14, 12, 11).map { it.sp }, styles(phone).map { it.fontSize })
        assertEquals(TextRole.entries.size, styles(phone).size)
    }

    @Test
    fun onATelevisionEveryTextIsOneStepLarger() {
        val small = styles(phone)
        val large = styles(television)
        for (i in small.indices) {
            assertTrue("${TextRole.entries[i]}", large[i].fontSize.value > small[i].fontSize.value)
            assertTrue("${TextRole.entries[i]}", large[i].lineHeight.value >= small[i].lineHeight.value)
            assertEquals(small[i].fontWeight, large[i].fontWeight)
        }
        assertEquals(16.sp, television.bodyMedium.fontSize)
        assertEquals(18.sp, television.bodyLarge.fontSize)
        assertEquals(24.sp, television.titleLarge.fontSize)
        assertEquals(12.sp, television.labelSmall.fontSize)
    }

    @Test
    fun aLineIsAlwaysHigherThanItsText() {
        for (style in styles(phone) + styles(television)) {
            assertTrue("${style.fontSize}", style.lineHeight.value >= style.fontSize.value * 1.1f)
        }
    }

    @Test
    fun titlesAreMediumAndScreenTitlesAStepHeavier() {
        assertEquals(FontWeight.Medium, phone.titleMedium.fontWeight)
        assertEquals(FontWeight.Medium, phone.titleSmall.fontWeight)
        assertEquals(FontWeight.SemiBold, phone.titleLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, phone.headlineSmall.fontWeight)
        assertEquals(FontWeight.Normal, phone.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, phone.titleMedium.heavier().fontWeight)
        assertEquals(FontWeight.Black, phone.titleMedium.copy(fontWeight = FontWeight.Black).heavier().fontWeight)
    }

    @Test
    fun figuresLineUpAndFingerprintsReadLeftToRight() {
        assertEquals("tnum", phone.bodyMedium.figures().fontFeatureSettings)
        val print = phone.bodyMedium.fingerprint()
        assertEquals(FontFamily.Monospace, print.fontFamily)
        assertEquals(TextDirection.Ltr, print.textDirection)
        assertEquals(phone.bodyMedium.fontSize, print.fontSize)
    }
}
