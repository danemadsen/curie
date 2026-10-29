package com.danemadsen.curie.ui.call.eclipse

import com.danemadsen.curie.ui.call.EclipseMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EclipseStyleTest {
    @Test
    fun listeningBreathesCalmlyAndIgnoresTheLevel() {
        val style = EclipseStyle.forMood(EclipseMood.LISTENING)

        assertEquals(1f, style.breathingDepth, 0f)
        assertEquals(0f, style.innerLevelGain, 0f)
        assertEquals(0f, style.streamerLevelGain, 0f)
        assertEquals(0f, style.glintStrength, 0f)
    }

    @Test
    fun theUsersVoiceBrightensOnlyTheInnerCorona() {
        val style = EclipseStyle.forMood(EclipseMood.USER_SPEAKING)

        assertTrue(style.innerLevelGain > 0f)
        assertEquals(0f, style.streamerLevelGain, 0f)
    }

    @Test
    fun curiesVoiceStretchesTheStreamers() {
        val speaking = EclipseStyle.forMood(EclipseMood.SPEAKING)
        val userSpeaking = EclipseStyle.forMood(EclipseMood.USER_SPEAKING)

        assertTrue(speaking.streamerLevelGain > 0f)
        assertTrue(speaking.innerLevelGain < userSpeaking.innerLevelGain)
    }

    @Test
    fun onlyThinkingShowsTheGlint() {
        for (mood in EclipseMood.entries) {
            val glint = EclipseStyle.forMood(mood).glintStrength
            val expected = if (mood == EclipseMood.THINKING) 1f else 0f

            assertEquals("$mood", expected, glint, 0f)
        }
    }

    @Test
    fun idleIsStillAndTheSlowest() {
        val idle = EclipseStyle.forMood(EclipseMood.IDLE)

        assertEquals(0f, idle.breathingDepth, 0f)
        assertEquals(0f, idle.innerLevelGain, 0f)
        assertEquals(0f, idle.streamerLevelGain, 0f)
        for (mood in EclipseMood.entries.filter { mood -> mood != EclipseMood.IDLE }) {
            val style = EclipseStyle.forMood(mood)
            assertTrue("$mood", style.driftRate > idle.driftRate && style.shimmerRate > idle.shimmerRate)
        }
    }

    @Test
    fun lerpRunsFromOneStyleToTheOther() {
        val from = EclipseStyle.forMood(EclipseMood.LISTENING)
        val to = EclipseStyle.forMood(EclipseMood.THINKING)
        val halfway = from.lerp(to, 0.5f)

        assertEquals(from, from.lerp(to, 0f))
        assertEquals(to, from.lerp(to, 1f))
        assertEquals((from.glintStrength + to.glintStrength) / 2f, halfway.glintStrength, 1e-6f)
        assertEquals((from.shimmerRate + to.shimmerRate) / 2f, halfway.shimmerRate, 1e-6f)
    }

    @Test
    fun onlyListeningAndIdleAreCalm() {
        val calmMoods = EclipseMood.entries.filter { mood -> EclipseStyle.forMood(mood).isCalm }

        assertEquals(listOf(EclipseMood.LISTENING, EclipseMood.IDLE), calmMoods)
    }

    @Test
    fun aCrossFadeIntoACalmMoodIsNotCalmUntilItEnds() {
        val thinking = EclipseStyle.forMood(EclipseMood.THINKING)
        val listening = EclipseStyle.forMood(EclipseMood.LISTENING)

        assertFalse(thinking.lerp(listening, 0.9f).isCalm)
        assertTrue(thinking.lerp(listening, 1f).isCalm)
    }
}
