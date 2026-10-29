package com.danemadsen.curie.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The samples of one spoken answer between the speech decoder and the speaker. The model often
 * speaks slower than real time on a phone, so playback starts only once [startSeconds] are
 * buffered (or the answer is complete), and when it runs dry before the end it pauses until
 * [rebufferSeconds] more are buffered: a few longer pauses instead of a stutter.
 *
 * Thread-safe: one thread writes, another reads (and may wait with [awaitChange]).
 */
class JitterBuffer(
    sampleRate: Int,
    startSeconds: Double = 1.0,
    rebufferSeconds: Double = 0.5,
) {
    enum class State {
        /** Waiting for enough samples to start or resume; [read] returns nothing. */
        BUFFERING,

        PLAYING,

        /** The answer is complete and every sample was read. */
        FINISHED,
    }

    private val startSamples = (sampleRate * startSeconds).toInt()
    private val rebufferSamples = (sampleRate * rebufferSeconds).toInt()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<FloatArray>()
    private var headOffset = 0
    private var buffered = 0
    private var threshold = startSamples
    private var isComplete = false
    private var currentState = State.BUFFERING
    private var underrunCount = 0

    val state: State
        get() = lock.withLock { currentState }

    /** Samples written and not read yet. */
    val bufferedSamples: Int
        get() = lock.withLock { buffered }

    /** How often playback ran dry and paused to re-buffer. */
    val underruns: Int
        get() = lock.withLock { underrunCount }

    fun write(samples: FloatArray) {
        if (samples.isEmpty()) {
            return
        }
        lock.withLock {
            if (isComplete) {
                return
            }
            chunks.addLast(samples)
            buffered += samples.size
            if (currentState == State.BUFFERING && buffered >= threshold) {
                currentState = State.PLAYING
            }
            changed.signalAll()
        }
    }

    /** No more samples will come: what is buffered plays out, however little it is. */
    fun complete() {
        lock.withLock {
            isComplete = true
            if (currentState == State.BUFFERING) {
                currentState = if (buffered > 0) State.PLAYING else State.FINISHED
            }
            changed.signalAll()
        }
    }

    /**
     * Up to [maxSamples] samples to play now; empty while buffering or once finished. Taking the
     * last buffered sample of an incomplete answer is an underrun: playback re-buffers.
     */
    fun read(maxSamples: Int): FloatArray {
        lock.withLock {
            if (currentState != State.PLAYING) {
                return EMPTY
            }

            val count = minOf(maxSamples, buffered)
            val samples = FloatArray(count)
            var filled = 0
            while (filled < count) {
                val head = chunks.first()
                val available = head.size - headOffset
                val taken = minOf(available, count - filled)
                head.copyInto(samples, destinationOffset = filled, startIndex = headOffset, endIndex = headOffset + taken)
                filled += taken
                headOffset += taken
                if (headOffset == head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            buffered -= count

            if (buffered == 0) {
                if (isComplete) {
                    currentState = State.FINISHED
                } else {
                    currentState = State.BUFFERING
                    threshold = rebufferSamples
                    underrunCount++
                }
            }
            changed.signalAll()
            return samples
        }
    }

    /** Starts over for a new answer: drops everything and buffers [startSeconds] again. */
    fun reset() {
        lock.withLock {
            chunks.clear()
            headOffset = 0
            buffered = 0
            threshold = startSamples
            isComplete = false
            currentState = State.BUFFERING
            underrunCount = 0
            changed.signalAll()
        }
    }

    /** Waits up to [timeoutMs] for a write, a completion or a reset. */
    fun awaitChange(timeoutMs: Long) {
        lock.withLock {
            changed.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
