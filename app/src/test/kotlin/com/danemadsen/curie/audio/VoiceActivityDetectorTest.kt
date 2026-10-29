package com.danemadsen.curie.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceActivityDetectorTest {
    private val settings = VadSettings()
    private val detector = VoiceActivityDetector(settings)
    private var time = 0

    /** [durationMs] of a 220 Hz tone at [amplitude] (0 for silence), fed in 20 ms chunks. */
    private fun feed(durationMs: Int, amplitude: Float = 0f): List<VadEvent> {
        val events = ArrayList<VadEvent>()
        val chunkSamples = settings.frameSamples
        repeat(durationMs / settings.frameMs) {
            val chunk = FloatArray(chunkSamples) { index ->
                val seconds = (time + index).toDouble() / settings.sampleRate
                (amplitude * sin(2 * PI * 220 * seconds)).toFloat()
            }
            time += chunkSamples
            events.addAll(detector.process(chunk))
        }
        return events
    }

    /** Speech-like: 200 ms syllables with 60 ms dips between them, as long as [durationMs]. */
    private fun feedSyllables(durationMs: Int): List<VadEvent> {
        val events = ArrayList<VadEvent>()
        var fed = 0
        while (fed < durationMs) {
            events.addAll(feed(durationMs = 200, amplitude = 0.3f))
            events.addAll(feed(durationMs = 60, amplitude = 0.001f))
            fed += 260
        }
        return events
    }

    private fun samplesOf(milliseconds: Int) = settings.sampleRate * milliseconds / 1000

    @Test
    fun silenceIsNotSpeech() {
        val events = feed(durationMs = 5_000)

        assertTrue(events.isEmpty())
        assertFalse(detector.isSpeaking)
    }

    @Test
    fun anUtteranceStartsAfter200MsOfSpeechAndEndsAfter700MsOfSilence() {
        feed(durationMs = 1_000)

        val beforeStart = feed(durationMs = 180, amplitude = 0.3f)
        assertTrue(beforeStart.isEmpty())
        val start = feed(durationMs = 20, amplitude = 0.3f)
        assertEquals(listOf<VadEvent>(VadEvent.SpeechStarted), start)
        assertTrue(detector.isSpeaking)

        feed(durationMs = 300, amplitude = 0.3f)
        val beforeEnd = feed(durationMs = 680)
        assertTrue(beforeEnd.isEmpty())
        val end = feed(durationMs = 20).single()

        // 300 ms of pre-roll, 500 ms of speech, 300 ms of the silence after it
        val ended = assertIs<VadEvent.SpeechEnded>(end)
        assertEquals(VadEvent.EndReason.SILENCE, ended.reason)
        assertEquals(samplesOf(1_100), ended.samples.size)
        assertFalse(detector.isSpeaking)
    }

    @Test
    fun thePreRollKeepsTheStartOfTheFirstSyllable() {
        feed(durationMs = 1_000)
        feed(durationMs = 500, amplitude = 0.3f)
        val ended = feed(durationMs = 800).filterIsInstance<VadEvent.SpeechEnded>().single()

        val preRoll = ended.samples.copyOfRange(0, samplesOf(settings.preRollMs))
        val speech = ended.samples.copyOfRange(samplesOf(settings.preRollMs), samplesOf(settings.preRollMs + 100))
        assertEquals(0f, AudioLevels.rms(preRoll))
        assertTrue(AudioLevels.rms(speech) > 0.1f)
    }

    @Test
    fun aShortSoundIsNotAnUtterance() {
        feed(durationMs = 500)
        val events = feed(durationMs = 100, amplitude = 0.5f) + feed(durationMs = 1_000)

        assertTrue(events.isEmpty())
    }

    @Test
    fun shortPausesDoNotResetSpeechThatIsStarting() {
        feed(durationMs = 500)
        val events = feed(durationMs = 120, amplitude = 0.3f) + feed(durationMs = 60) + feed(durationMs = 100, amplitude = 0.3f)

        assertEquals(listOf<VadEvent>(VadEvent.SpeechStarted), events)
    }

    @Test
    fun anUtteranceEndsAtThirtySeconds() {
        feed(durationMs = 500)
        val events = feedSyllables(durationMs = 31_000)

        val ended = events.filterIsInstance<VadEvent.SpeechEnded>().first()
        assertEquals(VadEvent.EndReason.MAX_LENGTH, ended.reason)
        assertEquals(samplesOf(settings.maxUtteranceMs), ended.samples.size)
    }

    @Test
    fun aBargeInNeedsLouderAndLongerSpeech() {
        detector.sensitivity = VadSensitivity.BARGE_IN
        feed(durationMs = 500)

        // -43 dBFS: speech when waiting for the user, not over Curie's voice
        val quiet = feed(durationMs = 1_000, amplitude = 0.01f)
        assertTrue(quiet.isEmpty())

        feed(durationMs = 500)
        val tooShort = feed(durationMs = 260, amplitude = 0.3f) + feed(durationMs = 500)
        assertTrue(tooShort.isEmpty())

        val bargeIn = feed(durationMs = 300, amplitude = 0.3f)
        assertEquals(listOf<VadEvent>(VadEvent.SpeechStarted), bargeIn)

        detector.reset()
        detector.sensitivity = VadSensitivity.NORMAL
        feed(durationMs = 500)
        val quietSpeech = feed(durationMs = 300, amplitude = 0.01f)
        assertEquals(listOf<VadEvent>(VadEvent.SpeechStarted), quietSpeech)
    }

    @Test
    fun steadyBackgroundNoiseIsLearnedAndOnlyLouderSpeechCounts() {
        // -30 dBFS of hum: the background, whatever the absolute level
        val hum = feed(durationMs = 4_000, amplitude = 0.045f)
        assertTrue(hum.isEmpty())

        val speech = feed(durationMs = 300, amplitude = 0.3f)
        assertEquals(listOf<VadEvent>(VadEvent.SpeechStarted), speech)
    }

    @Test
    fun resetForgetsTheUtteranceInProgress() {
        feed(durationMs = 500)
        feed(durationMs = 400, amplitude = 0.3f)
        assertTrue(detector.isSpeaking)

        detector.reset()
        val after = feed(durationMs = 1_000)

        assertFalse(detector.isSpeaking)
        assertTrue(after.isEmpty())
    }

    @Test
    fun theLevelFollowsTheLastFrame() {
        feed(durationMs = 100, amplitude = 0.3f)
        assertTrue(detector.level > 0.5f)

        feed(durationMs = 100)
        assertEquals(0f, detector.level)
    }
}
