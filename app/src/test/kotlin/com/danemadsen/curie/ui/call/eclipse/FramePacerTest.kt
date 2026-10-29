package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    @Test
    fun aLivelyEclipseDrawsEveryFrame() {
        val pacer = FramePacer()
        val drawn = countDrawn(pacer, frameNanos = 16_666_667L, frames = 60, calm = false)

        assertEquals(60, drawn)
    }

    @Test
    fun aCalmEclipseDrawsAboutThirtyFramesASecondAtAnyRefreshRate() {
        val refreshRates = listOf(60, 90, 120)
        for (refreshRate in refreshRates) {
            val pacer = FramePacer()
            val frameNanos = 1_000_000_000L / refreshRate
            val drawn = countDrawn(pacer, frameNanos, frames = refreshRate, calm = true)

            assertTrue("$drawn frames a second at $refreshRate Hz", drawn in 28..32)
        }
    }

    @Test
    fun theFirstFrameAlwaysDraws() {
        val pacer = FramePacer()

        assertTrue(pacer.shouldDraw(frameTimeNanos = 123L, calm = true))
    }

    @Test
    fun leavingCalmDrawsTheNextFrameAtOnce() {
        val pacer = FramePacer()
        pacer.shouldDraw(frameTimeNanos = 0L, calm = true)

        assertTrue(pacer.shouldDraw(frameTimeNanos = 16_666_667L, calm = false))
    }

    private fun countDrawn(pacer: FramePacer, frameNanos: Long, frames: Int, calm: Boolean): Int {
        var drawn = 0
        for (frame in 0 until frames) {
            val frameTime = 5_000_000_000L + frame * frameNanos
            if (pacer.shouldDraw(frameTime, calm)) {
                drawn += 1
            }
        }
        return drawn
    }
}
