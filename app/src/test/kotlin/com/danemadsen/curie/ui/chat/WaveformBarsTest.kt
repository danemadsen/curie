package com.danemadsen.curie.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformBarsTest {
    @Test
    fun scalesTheLoudestBarToFullHeight() {
        val bars = waveformBars(levels = listOf(0.1f, 0.4f, 0.2f, 0.2f), barCount = 4, seed = "clip")

        assertEquals(listOf(0.25f, 1f, 0.5f, 0.5f), bars)
    }

    @Test
    fun eachBarTakesTheLoudestLevelOfItsStretch() {
        val levels = listOf(0.1f, 0.8f, 0.2f, 0.4f, 0.4f, 0.1f)
        val bars = waveformBars(levels = levels, barCount = 3, seed = "clip")

        assertEquals(listOf(1f, 0.5f, 0.5f), bars)
    }

    @Test
    fun stretchesShortLevelListsOverAllBars() {
        val bars = waveformBars(levels = listOf(0.5f, 1f), barCount = 4, seed = "clip")

        assertEquals(listOf(0.5f, 0.5f, 1f, 1f), bars)
    }

    @Test
    fun quietStretchesKeepAMinimumHeight() {
        val bars = waveformBars(levels = listOf(0f, 1f), barCount = 2, seed = "clip")

        assertEquals(0.15f, bars.first(), 0.0001f)
    }

    @Test
    fun silenceDrawsMinimumBars() {
        val bars = waveformBars(levels = listOf(0f, 0f, 0f), barCount = 3, seed = "clip")

        assertEquals(listOf(0.15f, 0.15f, 0.15f), bars)
    }

    @Test
    fun withoutLevelsTheShapeDependsOnlyOnTheSeed() {
        val first = waveformBars(levels = emptyList(), barCount = 28, seed = "clip-1")
        val again = waveformBars(levels = emptyList(), barCount = 28, seed = "clip-1")
        val other = waveformBars(levels = emptyList(), barCount = 28, seed = "clip-2")

        assertEquals(first, again)
        assertTrue(first != other)
        assertTrue(first.all { height -> height in 0.15f..1f })
    }

    @Test
    fun clampsLevelsOutsideTheRange() {
        val bars = waveformBars(levels = listOf(-1f, 2f), barCount = 2, seed = "clip")

        assertEquals(listOf(0.15f, 1f), bars)
    }
}
