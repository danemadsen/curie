package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CoronaNoiseTest {
    @Test
    fun hashIsDeterministicAndInTheUnitInterval() {
        for (step in -2000..2000) {
            val input = step * 0.731f
            val value = CoronaNoise.hash(input)

            assertTrue("hash($input) = $value", value >= 0f && value < 1f)
            assertEquals(value, CoronaNoise.hash(input), 0f)
        }
    }

    @Test
    fun hashSpreadsNeighbouringIntegersOverTheInterval() {
        val values = (0 until 4000).map { cell -> CoronaNoise.hash(cell.toFloat()) }
        val mean = values.average()
        val variance = values.sumOf { value -> (value - mean) * (value - mean) } / values.size

        // A uniform distribution on [0, 1) has mean 1/2 and standard deviation 1/sqrt(12) = 0.289.
        assertEquals(0.5, mean, 0.03)
        assertEquals(0.289, sqrt(variance), 0.03)
    }

    @Test
    fun valueNoiseIsDeterministicAndInTheUnitInterval() {
        for (column in 0 until 60) {
            for (row in 0 until 60) {
                val x = column * 0.37f - 7f
                val y = row * 0.29f - 5f
                val value = CoronaNoise.valueNoise(x, y, periodX = 150f, periodY = 16f, seed = 101f)

                assertTrue("noise($x, $y) = $value", value in 0f..1f)
                assertEquals(value, CoronaNoise.valueNoise(x, y, 150f, 16f, 101f), 0f)
            }
        }
    }

    @Test
    fun valueNoiseRepeatsWithItsPeriods() {
        val periodX = 64f
        val periodY = 16f
        for (step in 0 until 200) {
            val x = step * 0.613f
            val y = step * 0.177f
            val value = CoronaNoise.valueNoise(x, y, periodX, periodY, seed = 7f)
            val acrossX = CoronaNoise.valueNoise(x + periodX, y, periodX, periodY, seed = 7f)
            val acrossY = CoronaNoise.valueNoise(x, y - periodY, periodX, periodY, seed = 7f)

            assertEquals(value, acrossX, 1e-3f)
            assertEquals(value, acrossY, 1e-3f)
        }
    }

    @Test
    fun valueNoiseMatchesTheHashAtLatticePoints() {
        val seed = 202f
        for (cell in 0 until 20) {
            val x = cell.toFloat()
            val y = 3f
            val expected = CoronaNoise.hash(x + y * CoronaNoise.ROW_STRIDE + seed)

            assertEquals(expected, CoronaNoise.valueNoise(x, y, 330f, 16f, seed), 1e-6f)
        }
    }

    @Test
    fun valueNoiseIsContinuous() {
        var previous = CoronaNoise.valueNoise(0f, 0.5f, 720f, 16f, seed = 303f)
        var x = 0f
        while (x < 50f) {
            x += 0.001f
            val value = CoronaNoise.valueNoise(x, 0.5f, 720f, 16f, seed = 303f)

            assertTrue("jump of ${abs(value - previous)} at x = $x", abs(value - previous) < 0.01f)
            previous = value
        }
    }

    @Test
    fun seedsGiveIndependentPatterns() {
        var matching = 0
        for (cell in 0 until 500) {
            val x = cell * 0.5f
            val first = CoronaNoise.valueNoise(x, 1.5f, 1024f, 16f, seed = 101f)
            val second = CoronaNoise.valueNoise(x, 1.5f, 1024f, 16f, seed = 202f)
            if (abs(first - second) < 0.02f) {
                matching += 1
            }
        }

        assertTrue("$matching of 500 samples match", matching < 50)
    }

    @Test
    fun oneDimensionalNoiseIsSmoothRepeatsAndMatchesTheHash() {
        val period = 8f
        val seed = 505f
        var previous = CoronaNoise.valueNoise1D(0f, period, seed)
        var x = 0f
        while (x < 20f) {
            x += 0.001f
            val value = CoronaNoise.valueNoise1D(x, period, seed)

            assertTrue("noise($x) = $value", value in 0f..1f)
            assertTrue("jump at $x", abs(value - previous) < 0.01f)
            assertEquals(value, CoronaNoise.valueNoise1D(x + period, period, seed), 1e-3f)
            previous = value
        }
        assertEquals(CoronaNoise.hash(3f + seed), CoronaNoise.valueNoise1D(3f, period, seed), 1e-6f)
    }
}
