package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Test

class CoronaMathTest {
    @Test
    fun fractIsInTheUnitIntervalForNegativeValuesToo() {
        assertEquals(0.25f, fract(3.25f), 1e-6f)
        assertEquals(0.75f, fract(-3.25f), 1e-6f)
        assertEquals(0f, fract(-2f), 0f)
    }

    @Test
    fun floorModTakesTheSignOfThePeriod() {
        assertEquals(1f, floorMod(-15f, 16f), 0f)
        assertEquals(0f, floorMod(16f, 16f), 0f)
        assertEquals(3.5f, floorMod(19.5f, 16f), 0f)
    }

    @Test
    fun smoothStepRisesSmoothlyBetweenItsEdges() {
        assertEquals(0f, smoothStep(0.2f, 0.6f, 0.1f), 0f)
        assertEquals(0.5f, smoothStep(0.2f, 0.6f, 0.4f), 1e-6f)
        assertEquals(1f, smoothStep(0.2f, 0.6f, 0.9f), 0f)
    }
}
