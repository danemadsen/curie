package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoronaShapeTest {
    @Test
    fun phasesSwayWithinBoundsAndChangeSmoothly() {
        val frameSeconds = 1.0 / 60.0
        var previous = phaseList(CoronaShape.phasesAt(0.0))
        var seconds = 0.0
        while (seconds < 600.0) {
            seconds += frameSeconds
            val current = phaseList(CoronaShape.phasesAt(seconds))
            for (index in current.indices) {
                val change = abs(current[index] - previous[index])
                assertTrue("phase $index jumps by $change at $seconds s", change < 0.005f)
                assertTrue("phase $index strays to ${current[index]}", abs(current[index]) < 7f)
            }
            previous = current
        }
    }

    @Test
    fun theShapeMorphsOverAMinuteOrTwo() {
        val start = phaseList(CoronaShape.phasesAt(0.0))
        val later = phaseList(CoronaShape.phasesAt(45.0))
        val changes = start.zip(later) { before, after -> abs(after - before) }

        assertTrue("phases barely move: $changes", changes.count { change -> change > 0.3f } >= 4)
    }

    @Test
    fun flowStaysWithinItsPeriodForever() {
        val samples = listOf(0.0, 1.5, 59.9, 3600.0, 86_400.0, 1e7)
        for (seconds in samples) {
            val flow = CoronaShape.flowAt(seconds)
            for (offset in listOf(flow.plumes, flow.fine, flow.finer, flow.finest)) {
                assertTrue("flow $offset at $seconds s", offset >= 0f && offset <= CoronaLook.FLOW_PERIOD)
            }
        }
    }

    @Test
    fun flowWrapsWithoutAJumpInTheTexture() {
        // Just before and just after the fine flow wraps, the texture it moves must be continuous.
        val finePeriodSeconds = CoronaLook.FLOW_PERIOD / CoronaShape.FINE_FLOW_RATE
        val before = CoronaShape.flowAt(finePeriodSeconds - 1e-3).fine
        val after = CoronaShape.flowAt(finePeriodSeconds + 1e-3).fine
        assertTrue("the flow did not wrap: $before -> $after", before > after)

        val cells = CoronaLook.FINE_CELLS
        val period = CoronaLook.FLOW_PERIOD
        val seed = CoronaLook.FINE_SEED
        val noiseBefore = CoronaNoise.valueNoise(37.3f, 0.4f - before, cells, period, seed)
        val noiseAfter = CoronaNoise.valueNoise(37.3f, 0.4f - after, cells, period, seed)
        assertEquals(noiseBefore, noiseAfter, 1e-3f)
    }

    @Test
    fun eachBeadFlickersOnItsOwnAndStaysBright() {
        val flickers = (0 until 2000).map { step -> CoronaShape.beadFlickerAt(step * 0.05) }
        val mains = flickers.map { flicker -> flicker.main }
        val seconds = flickers.map { flicker -> flicker.second }
        val thirds = flickers.map { flicker -> flicker.third }
        val brightRange = CoronaShape.BEAD_FLICKER_FLOOR..1f

        for (values in listOf(mains, seconds, thirds)) {
            assertTrue(values.all { value -> value in brightRange })
            assertNotEquals(values.min(), values.max(), 0.1f)
        }
        val secondDiffers = mains.indices.count { index -> mains[index] != seconds[index] }
        val thirdDiffers = mains.indices.count { index -> mains[index] != thirds[index] }
        assertTrue("the second bead flickers in step with the main one", secondDiffers > 1000)
        assertTrue("the third bead flickers in step with the main one", thirdDiffers > 1000)
    }

    private fun phaseList(phases: CoronaPhases): List<Float> = listOf(
        phases.streamers.x,
        phases.streamers.y,
        phases.streamers.z,
        phases.streamers.w,
        phases.bend1,
        phases.bend2,
        phases.unevenness1,
        phases.unevenness2,
    )
}
