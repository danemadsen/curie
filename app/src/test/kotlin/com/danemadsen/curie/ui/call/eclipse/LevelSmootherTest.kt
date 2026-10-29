package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class LevelSmootherTest {
    private fun smoother() = LevelSmoother(attackSeconds = 0.06f, releaseSeconds = 0.3f)

    @Test
    fun risesWithTheAttackTimeConstant() {
        val level = smoother()
        val afterOneTimeConstant = level.update(1f, elapsedSeconds = 0.06f)

        assertEquals(1f - exp(-1f), afterOneTimeConstant, 1e-5f)
    }

    @Test
    fun fallsWithTheSlowerReleaseTimeConstant() {
        val level = smoother()
        level.update(1f, elapsedSeconds = 10f)
        val afterOneTimeConstant = level.update(0f, elapsedSeconds = 0.3f)

        assertEquals(exp(-1f), afterOneTimeConstant, 1e-5f)
    }

    @Test
    fun attackIsFasterThanRelease() {
        val rising = smoother()
        val falling = smoother()
        falling.update(1f, elapsedSeconds = 10f)

        val risen = rising.update(1f, elapsedSeconds = 0.05f)
        val fallen = 1f - falling.update(0f, elapsedSeconds = 0.05f)
        assertTrue("rose $risen, fell $fallen", risen > 2f * fallen)
    }

    @Test
    fun theFrameRateDoesNotChangeTheResponse() {
        val coarse = smoother()
        val fine = smoother()
        coarse.update(0.8f, elapsedSeconds = 0.048f)
        repeat(3) { fine.update(0.8f, elapsedSeconds = 0.016f) }

        assertEquals(coarse.value, fine.value, 1e-5f)
    }

    @Test
    fun clampsTheLevelAndTreatsNaNAsSilence() {
        val level = smoother()
        level.update(5f, elapsedSeconds = 10f)
        assertEquals(1f, level.value, 1e-6f)

        level.update(Float.NaN, elapsedSeconds = 10f)
        assertEquals(0f, level.value, 1e-6f)

        level.update(-3f, elapsedSeconds = 1f)
        assertEquals(0f, level.value, 0f)
    }
}
