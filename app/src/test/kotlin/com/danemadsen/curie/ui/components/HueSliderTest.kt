package com.danemadsen.curie.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class HueSliderTest {
    @Test
    fun convertsPrimaryColoursToHsv() {
        assertHsv(Hsv(0f, 1f, 1f), Color.Red.toHsv())
        assertHsv(Hsv(120f, 1f, 1f), Color.Green.toHsv())
        assertHsv(Hsv(240f, 1f, 1f), Color.Blue.toHsv())
        assertHsv(Hsv(0f, 0f, 128f / 255f), Color(0xFF808080).toHsv())
    }

    @Test
    fun convertsMaidsAccentToHsv() {
        // #2196F3: what reanimated-color-picker starts from
        val hsv = Color(0xFF2196F3).toHsv()

        assertEquals(206.57f, hsv.hue, 0.01f)
        assertEquals(0.8642f, hsv.saturation, 0.0001f)
        assertEquals(0.9529f, hsv.value, 0.0001f)
    }

    @Test
    fun changingTheHueKeepsSaturationAndBrightness() {
        val accent = Color(0xFF2196F3)
        val original = accent.toHsv()
        val shifted = withHue(accent, 30f).toHsv()

        assertEquals(30f, shifted.hue, 0.5f)
        assertEquals(original.saturation, shifted.saturation, 0.01f)
        assertEquals(original.value, shifted.value, 0.01f)
    }

    @Test
    fun hueRoundTripsThroughTheSlider() {
        val accent = Color(0xFF2196F3)
        val sameHue = withHue(accent, accent.toHsv().hue)

        assertEquals(accent.toArgb(), sameHue.toArgb())
    }

    @Test
    fun fullTurnWrapsToRed() {
        val atEnd = withHue(Color.Blue, 360f)

        assertEquals(Color.Red.toArgb(), atEnd.toArgb())
    }

    private fun assertHsv(expected: Hsv, actual: Hsv) {
        assertEquals(expected.hue, actual.hue, 0.001f)
        assertEquals(expected.saturation, actual.saturation, 0.001f)
        assertEquals(expected.value, actual.value, 0.001f)
    }
}
