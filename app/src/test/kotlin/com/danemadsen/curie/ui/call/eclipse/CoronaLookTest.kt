package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CoronaLookTest {
    /** Both renderers take every colour from [CoronaLook], so a red or pink one would show up in both. */
    @Test
    fun noColourIsRedOrPink() {
        val palette = paletteColours()
        assertTrue("found only ${palette.keys}", palette.size >= MIN_PALETTE_SIZE)

        for ((name, colour) in palette) {
            val weakest = minOf(colour.green, colour.blue)
            assertTrue("$name leans red: $colour", colour.red <= MAX_WARMTH * weakest)
        }
    }

    /** The streamers' strength may only reach 1 at the harmonics' very crest, so it never clips flat. */
    @Test
    fun theStreamerFieldTopsOutAtTheHarmonicsLargestValue() {
        val weights = CoronaLook.STREAMER_WEIGHTS
        val largestHarmonics = CoronaLook.STREAMER_HARMONICS_MEAN + weights.x + weights.y + weights.z + weights.w

        assertEquals(largestHarmonics, CoronaLook.STREAMER_FIELD_HIGH, 1e-6f)
        assertTrue(CoronaLook.STREAMER_FIELD_LOW < CoronaLook.STREAMER_FIELD_HIGH)
    }

    @Test
    fun theSpikeLinesAreSixtyDegreesApart() {
        val directions = CoronaLook.SPIKE_DIRECTIONS
        val sixtyDegrees = PI.toFloat() / 3f

        assertEquals(3, directions.size)
        assertEquals(sixtyDegrees, directions[1] - directions[0], 1e-6f)
        assertEquals(sixtyDegrees, directions[2] - directions[1], 1e-6f)
    }

    /** Every colour [CoronaLook] defines, found by reflection so that a new one cannot slip past this test. */
    private fun paletteColours(): Map<String, Rgb> {
        val fields = CoronaLook::class.java.declaredFields
        val colourFields = fields.filter { field -> field.type == Rgb::class.java }
        return colourFields.associate { field ->
            field.isAccessible = true
            field.name to field.get(null) as Rgb
        }
    }

    private companion object {
        /** The inner and outer tints, the sky and its glow, the stars and the Baily's beads. */
        const val MIN_PALETTE_SIZE = 6

        /**
         * Red may exceed the weaker of green and blue by 12 % at most: the outer corona's faint warmth
         * (1 : 0.96 : 0.9) passes, while a pink such as (1 : 0.2 : 0.4) fails by far.
         */
        const val MAX_WARMTH = 1.12f
    }
}
