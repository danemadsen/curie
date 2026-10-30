package com.danemadsen.curie.audio

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When playback starts: soon when speech arrives fast enough, late enough to play through when not. */
class JitterBufferTest {
    /** 1,000 samples a second keeps the arithmetic readable. */
    private val sampleRate = 1_000
    private var nowNanos = 0L
    private val buffer = JitterBuffer(sampleRate) { nowNanos }

    private fun samples(count: Int, from: Int = 0) = FloatArray(count) { index -> (from + index).toFloat() }

    private fun advance(milliseconds: Long) {
        nowNanos += milliseconds * 1_000_000
    }

    /**
     * Speech of [totalMs] made at [speed] times real time in 80 ms frames and delivered in bursts of
     * [framesPerWrite] frames, as the speech decoder does, then played in 20 ms steps as a speaker
     * would: returns how often playback ran dry and how long the listener waited for the first sound.
     */
    private fun simulate(totalMs: Int, speed: Double, expectedMs: Int? = null, framesPerWrite: Int = 12): Pair<Int, Int> {
        if (expectedMs != null) {
            buffer.expectDuration(expectedMs / 1_000.0)
        }
        val frameMs = 80
        val msPerFrame = frameMs / speed
        var made = 0
        var pendingFrames = 0
        var nextFrameAt = msPerFrame
        var clockMs = 0
        var startedAtMs = -1

        while (buffer.state != JitterBuffer.State.FINISHED) {
            clockMs += 20
            advance(20)
            while (made < totalMs && clockMs >= nextFrameAt) {
                made += frameMs
                pendingFrames++
                nextFrameAt += msPerFrame
            }
            val isLastBurst = made >= totalMs && pendingFrames > 0
            if (pendingFrames >= framesPerWrite || isLastBurst) {
                buffer.write(samples(pendingFrames * frameMs * sampleRate / 1_000))
                pendingFrames = 0
            }
            if (made >= totalMs && pendingFrames == 0) {
                buffer.complete()
            }
            val played = buffer.read(20 * sampleRate / 1_000)
            if (played.isNotEmpty() && startedAtMs < 0) {
                startedAtMs = clockMs
            }
        }
        return buffer.underruns to startedAtMs
    }

    @Test
    fun speechFasterThanRealTimeStartsWithItsSecondBurst() {
        val (underruns, startedAtMs) = simulate(totalMs = 6_000, speed = 1.3)

        assertEquals(0, underruns)
        assertTrue(startedAtMs in 1_400..1_600, "started at $startedAtMs ms")
    }

    @Test
    fun aTextReadAloudAtNearRealTimePlaysThrough() {
        // Measured on the emulator: 9 s of speech at 0.87x real time, in the decoder's 0.96 s bursts
        val (underruns, startedAtMs) = simulate(totalMs = 9_000, speed = 0.87, expectedMs = 9_400)

        assertEquals(0, underruns)
        assertTrue(startedAtMs < 4_000, "started at $startedAtMs ms")
    }

    @Test
    fun slowSpeechOfAKnownLengthPlaysThroughWithoutAPause() {
        // At half speed a 10 s text takes 20 s to make: playback starts about half way
        val (underruns, startedAtMs) = simulate(totalMs = 10_000, speed = 0.5, expectedMs = 10_000)

        assertEquals(0, underruns)
        assertTrue(startedAtMs in 10_000..15_000, "started at $startedAtMs ms")
    }

    @Test
    fun slowSpeechOfUnknownLengthPausesAtMostOnce() {
        // The 8 s an unknown answer is taken to last cover a call's answers; a longer one pauses once
        val (shortUnderruns, _) = simulate(totalMs = 7_000, speed = 0.7)
        assertEquals(0, shortUnderruns)

        buffer.reset()
        val (longUnderruns, _) = simulate(totalMs = 20_000, speed = 0.7)
        assertTrue(longUnderruns <= 1, "$longUnderruns underruns")
    }

    @Test
    fun anAnswerLongerThanExpectedStillPausesRarely() {
        val (underruns, _) = simulate(totalMs = 12_000, speed = 0.6, expectedMs = 8_000)

        assertTrue(underruns <= 2, "$underruns underruns")
    }

    @Test
    fun theFirstBurstDoesNotCountTowardsTheSpeed() {
        // Its samples were made before it arrived, in a time that cannot be measured
        assertEquals(0.0, buffer.arrivalSpeed)
        buffer.write(samples(960))
        advance(1_100)
        buffer.write(samples(960))

        assertEquals(960.0 / 1_100.0, buffer.arrivalSpeed, absoluteTolerance = 1e-9)
    }

    @Test
    fun nothingPlaysBeforeHalfASecondIsBuffered() {
        buffer.write(samples(300))
        advance(2_000)
        buffer.write(samples(100))

        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertEquals(0, buffer.read(100).size)
    }

    @Test
    fun samplesComeOutInOrderAcrossWrites() {
        buffer.write(samples(300))
        buffer.write(samples(300, from = 300))
        buffer.write(samples(400, from = 600))
        buffer.complete()

        val played = ArrayList<Float>()
        while (buffer.bufferedSamples > 0) {
            played.addAll(buffer.read(256).toList())
        }

        assertEquals(samples(1_000).toList(), played)
    }

    @Test
    fun aCompleteAnswerPlaysEvenWhenShort() {
        buffer.write(samples(200))
        buffer.complete()

        assertEquals(JitterBuffer.State.PLAYING, buffer.state)
        assertContentEquals(samples(200), buffer.read(1_000))
        assertEquals(JitterBuffer.State.FINISHED, buffer.state)
        assertEquals(0, buffer.read(1_000).size)
    }

    @Test
    fun anEmptyCompleteAnswerIsFinishedAtOnce() {
        buffer.complete()

        assertEquals(JitterBuffer.State.FINISHED, buffer.state)
    }

    @Test
    fun writesAfterTheEndAreIgnored() {
        buffer.write(samples(100))
        buffer.complete()
        buffer.write(samples(5_000))

        assertEquals(100, buffer.bufferedSamples)
    }

    @Test
    fun resetStartsANewAnswerOfUnknownLength() {
        buffer.expectDuration(1.0)
        buffer.write(samples(1_500))
        buffer.complete()
        buffer.reset()

        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertEquals(0, buffer.bufferedSamples)
        assertEquals(0, buffer.underruns)
        buffer.write(samples(999))
        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertTrue(buffer.read(10).isEmpty())
    }
}
