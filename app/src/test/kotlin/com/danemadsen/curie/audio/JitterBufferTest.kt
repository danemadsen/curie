package com.danemadsen.curie.audio

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JitterBufferTest {
    /** 1,000 samples a second keeps the arithmetic readable: start at 1,000, re-buffer 500. */
    private val buffer = JitterBuffer(sampleRate = 1_000)

    private fun samples(count: Int, from: Int = 0) = FloatArray(count) { index -> (from + index).toFloat() }

    @Test
    fun playbackWaitsForASecondOfSpeech() {
        buffer.write(samples(600))
        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertEquals(0, buffer.read(100).size)

        buffer.write(samples(400, from = 600))
        assertEquals(JitterBuffer.State.PLAYING, buffer.state)
        assertContentEquals(samples(100), buffer.read(100))
    }

    @Test
    fun samplesComeOutInOrderAcrossWrites() {
        buffer.write(samples(300))
        buffer.write(samples(300, from = 300))
        buffer.write(samples(400, from = 600))

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
        assertEquals(200, buffer.read(1_000).size)
        assertEquals(JitterBuffer.State.FINISHED, buffer.state)
        assertEquals(0, buffer.read(1_000).size)
    }

    @Test
    fun anEmptyCompleteAnswerIsFinishedAtOnce() {
        buffer.complete()

        assertEquals(JitterBuffer.State.FINISHED, buffer.state)
    }

    @Test
    fun runningDryPausesUntilHalfASecondIsBuffered() {
        buffer.write(samples(1_000))
        buffer.read(1_000)

        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertEquals(1, buffer.underruns)

        buffer.write(samples(400))
        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        buffer.write(samples(100))
        assertEquals(JitterBuffer.State.PLAYING, buffer.state)
    }

    @Test
    fun writesAfterTheEndAreIgnored() {
        buffer.write(samples(100))
        buffer.complete()
        buffer.write(samples(5_000))

        assertEquals(100, buffer.bufferedSamples)
    }

    @Test
    fun resetStartsANewAnswer() {
        buffer.write(samples(1_500))
        buffer.complete()
        buffer.reset()

        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertEquals(0, buffer.bufferedSamples)
        buffer.write(samples(999))
        assertEquals(JitterBuffer.State.BUFFERING, buffer.state)
        assertTrue(buffer.read(10).isEmpty())
    }
}
