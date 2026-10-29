package com.danemadsen.curie.ui.call.eclipse

import com.danemadsen.curie.ui.call.EclipseMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class EclipseMotionTest {
    private val frameNanos = 16_666_667L
    private val startNanos = 5_000_000_000L

    @Test
    fun theFirstFrameStartsTheClocks() {
        val motion = EclipseMotion(EclipseMood.LISTENING)
        val first = motion.advance(startNanos, EclipseMood.LISTENING, level = 0f)
        val second = motion.advance(startNanos + frameNanos, EclipseMood.LISTENING, level = 0f)

        assertEquals(0.0, first.driftSeconds, 0.0)
        assertEquals(0.0, first.shimmerSeconds, 0.0)
        assertEquals(frameNanos / 1e9, second.driftSeconds, 1e-9)
    }

    @Test
    fun moodsCrossFadeOverFourHundredMilliseconds() {
        val motion = EclipseMotion(EclipseMood.LISTENING)
        var time = startNanos
        repeat(60) {
            motion.advance(time, EclipseMood.LISTENING, level = 0f)
            time += frameNanos
        }

        val switchTime = time
        var previousGlint = 0f
        var halfwayGlint = -1f
        while (time <= switchTime + 600_000_000L) {
            val frame = motion.advance(time, EclipseMood.THINKING, level = 0f)
            val sinceSwitch = time - switchTime
            assertTrue("glint jumps at $sinceSwitch ns", frame.glintStrength - previousGlint < 0.1f)
            if (sinceSwitch in 190_000_000L..210_000_000L) {
                halfwayGlint = frame.glintStrength
            }
            if (sinceSwitch >= 400_000_000L) {
                assertEquals(1f, frame.glintStrength, 0f)
            }
            previousGlint = frame.glintStrength
            time += frameNanos
        }

        assertTrue("halfway glint $halfwayGlint", halfwayGlint in 0.3f..0.7f)
    }

    @Test
    fun curiesVoiceStretchesAndBrightensTheStreamers() {
        val frame = settle(EclipseMood.SPEAKING, level = 1f)

        assertTrue("reach ${frame.gains.reach}", frame.gains.reach > 1.5f)
        assertTrue("streamers ${frame.gains.streamers}", frame.gains.streamers > 1.5f)
    }

    @Test
    fun theUsersVoiceBrightensTheInnerCorona() {
        val frame = settle(EclipseMood.USER_SPEAKING, level = 1f)

        assertTrue("inner ${frame.gains.inner}", frame.gains.inner > 1.3f)
        assertTrue("reach ${frame.gains.reach}", frame.gains.reach < 1.05f)
    }

    @Test
    fun listeningBreathesItsExposureButIgnoresTheLevel() {
        val motion = EclipseMotion(EclipseMood.LISTENING)
        var lowest = Float.MAX_VALUE
        var highest = Float.MIN_VALUE
        var time = startNanos
        repeat(60 * 7) {
            val frame = motion.advance(time, EclipseMood.LISTENING, level = 1f)
            lowest = minOf(lowest, frame.gains.exposure)
            highest = maxOf(highest, frame.gains.exposure)
            assertEquals(1f, frame.gains.inner, 0f)
            assertEquals(1f, frame.gains.streamers, 0f)
            time += frameNanos
        }

        val depth = EclipseMotion.BREATH_EXPOSURE
        assertEquals("deepest breath out", 1f - depth, lowest, 0.005f)
        assertEquals("deepest breath in", 1f + depth, highest, 0.005f)
    }

    @Test
    fun thinkingDimsTheExposureForTheGlint() {
        val frame = settle(EclipseMood.THINKING, level = 0f)
        val expected = 1f - CoronaLook.GLINT_EXPOSURE_DIP
        val breathingRoom = 0.3f * EclipseMotion.BREATH_EXPOSURE

        assertEquals(expected, frame.gains.exposure, breathingRoom)
    }

    /** The fallback renders the corona at the extreme exposures and mixes them, so no frame may go beyond. */
    @Test
    fun everyExposureStaysWithinTheRenderedRange() {
        val exposureRange = EclipseMotion.LOWEST_EXPOSURE..EclipseMotion.HIGHEST_EXPOSURE
        for (mood in EclipseMood.entries) {
            val motion = EclipseMotion(mood)
            var time = startNanos
            repeat(60 * 7) {
                val frame = motion.advance(time, mood, level = 1f)

                assertTrue("$mood exposure ${frame.gains.exposure}", frame.gains.exposure in exposureRange)
                time += frameNanos
            }
        }
    }

    @Test
    fun withoutMotionTheClocksStopButTheVoiceStillShows() {
        val motion = EclipseMotion(EclipseMood.SPEAKING)
        val first = motion.advance(startNanos, EclipseMood.SPEAKING, level = 0f, motionAllowed = false)
        var time = startNanos
        var frame = first
        repeat(60) {
            time += frameNanos
            frame = motion.advance(time, EclipseMood.SPEAKING, level = 1f, motionAllowed = false)
        }

        assertEquals(first.driftSeconds, frame.driftSeconds, 0.0)
        assertEquals(first.shimmerSeconds, frame.shimmerSeconds, 0.0)
        assertEquals(first.glintAngle, frame.glintAngle, 0f)
        assertEquals(first.gains.exposure, frame.gains.exposure, 0f)
        assertTrue("reach ${frame.gains.reach}", frame.gains.reach > 1.5f)
    }

    @Test
    fun listeningIsCalmAndThinkingIsNot() {
        val listening = EclipseMotion(EclipseMood.LISTENING)
        val thinking = EclipseMotion(EclipseMood.THINKING)
        listening.advance(startNanos, EclipseMood.LISTENING, level = 0f)
        thinking.advance(startNanos, EclipseMood.THINKING, level = 0f)

        assertTrue(listening.isCalm)
        assertFalse(thinking.isCalm)
    }

    @Test
    fun idleIsStill() {
        val motion = EclipseMotion(EclipseMood.IDLE)
        var time = startNanos
        repeat(60 * 7) {
            val frame = motion.advance(time, EclipseMood.IDLE, level = 1f)

            assertEquals(CoronaGains(), frame.gains)
            assertEquals(0f, frame.glintStrength, 0f)
            time += frameNanos
        }
    }

    @Test
    fun aLongPauseCountsAsAShortStep() {
        val motion = EclipseMotion(EclipseMood.LISTENING)
        motion.advance(startNanos, EclipseMood.LISTENING, level = 0f)
        val afterPause = motion.advance(startNanos + 30_000_000_000L, EclipseMood.LISTENING, level = 0f)

        assertEquals(EclipseMotion.MAX_FRAME_SECONDS, afterPause.driftSeconds, 1e-9)
    }

    @Test
    fun theGlintTravelsRoundTheLimbAtItsPace() {
        val motion = EclipseMotion(EclipseMood.THINKING)
        val first = motion.advance(startNanos, EclipseMood.THINKING, level = 0f)
        var time = startNanos
        var frame = first
        repeat(60) {
            time += frameNanos
            frame = motion.advance(time, EclipseMood.THINKING, level = 0f)
        }
        val elapsedSeconds = (time - startNanos) / 1e9
        val expected = (elapsedSeconds * 2.0 * PI / EclipseMotion.GLINT_PERIOD_SECONDS).toFloat()

        // The bead travels clockwise, so its angle falls.
        val travelled = (first.glintAngle - frame.glintAngle).mod(TAU)
        assertEquals(expected, travelled, 1e-3f)

        repeat(60 * 30) {
            time += frameNanos
            frame = motion.advance(time, EclipseMood.THINKING, level = 0f)
            assertTrue(frame.glintAngle >= 0f && frame.glintAngle < 2f * PI.toFloat())
        }
    }

    @Test
    fun aStillFrameShowsTheLevelAsGiven() {
        val speaking = EclipseMotion.stillFrame(EclipseMood.SPEAKING, level = 1f)
        val silent = EclipseMotion.stillFrame(EclipseMood.SPEAKING, level = Float.NaN)

        assertTrue(speaking.gains.reach > 1.3f)
        assertEquals(CoronaGains(), silent.gains)
        assertEquals(0.0, speaking.driftSeconds, 0.0)
    }

    private fun settle(mood: EclipseMood, level: Float): EclipseFrame {
        val motion = EclipseMotion(mood)
        var time = startNanos
        var frame = motion.advance(time, mood, level)
        repeat(60) {
            time += frameNanos
            frame = motion.advance(time, mood, level)
        }
        return frame
    }
}
