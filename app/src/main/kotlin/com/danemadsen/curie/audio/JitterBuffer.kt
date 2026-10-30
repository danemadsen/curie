package com.danemadsen.curie.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The samples of one spoken answer between the speech decoder and the speaker. On a phone the
 * model often speaks slower than real time, and starting as soon as a little speech is in gives a
 * pause every second or so. So playback waits until it can play to the end without running dry:
 * speech that arrives at `r` seconds a second, with `R` seconds still to come, plays through once
 * `R * (1 / r - 1)` seconds are buffered. `r` is measured as the speech arrives; `R` comes from the
 * answer's length when it is known ([expectDuration], for a text read aloud), and is otherwise taken
 * to be at least as long again as the answer so far, and no less than [ASSUMED_REMAINING_SECONDS].
 * Speech arrives in bursts (the decoder's chunks, about a second each), so the buffer also has to
 * bridge the longest wait between two of them. Should it run dry anyway, it waits again by the same
 * rule, now knowing a longer answer.
 *
 * Thread-safe: one thread writes, another reads (and may wait with [awaitChange]).
 */
class JitterBuffer(
    private val sampleRate: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class State {
        /** Waiting until playback can go on without running dry; [read] returns nothing. */
        BUFFERING,

        PLAYING,

        /** The answer is complete and every sample was read. */
        FINISHED,
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<FloatArray>()
    private var headOffset = 0
    private var buffered = 0

    /** Samples written for this answer, played or not. */
    private var produced = 0L

    /** The first write's samples were made before it, in a time that cannot be measured. */
    private var firstWriteSamples = 0
    private var firstWriteNanos = 0L
    private var lastWriteNanos = 0L
    private var longestGapNanos = 0L
    private var expectedSamples: Long? = null
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

    /**
     * Seconds of speech that arrived a second, after the first write: below 1 the model speaks
     * slower than real time. 0 until speech has arrived for a while.
     */
    val arrivalSpeed: Double
        get() = lock.withLock { measuredSpeed() ?: 0.0 }

    /** The answer's whole length, when it can be told in advance (a text read aloud). */
    fun expectDuration(seconds: Double) {
        lock.withLock {
            expectedSamples = (seconds * sampleRate).toLong()
            startIfReady()
        }
    }

    fun write(samples: FloatArray) {
        if (samples.isEmpty()) {
            return
        }
        lock.withLock {
            if (isComplete) {
                return
            }
            val now = nanoTime()
            if (produced == 0L) {
                firstWriteNanos = now
                firstWriteSamples = samples.size
            } else {
                longestGapNanos = maxOf(longestGapNanos, now - lastWriteNanos)
            }
            lastWriteNanos = now
            chunks.addLast(samples)
            buffered += samples.size
            produced += samples.size
            startIfReady()
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
                    underrunCount++
                }
            }
            changed.signalAll()
            return samples
        }
    }

    /** Starts over for a new answer: drops everything, and the next answer's length is unknown. */
    fun reset() {
        lock.withLock {
            chunks.clear()
            headOffset = 0
            buffered = 0
            produced = 0L
            firstWriteSamples = 0
            longestGapNanos = 0L
            expectedSamples = null
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

    /** Called with the lock held. */
    private fun startIfReady() {
        if (currentState == State.BUFFERING && canPlayThrough()) {
            currentState = State.PLAYING
            changed.signalAll()
        }
    }

    /** Whether what is buffered lasts until the rest of the answer has arrived. Called with the lock held. */
    private fun canPlayThrough(): Boolean {
        val bufferedSeconds = buffered.toDouble() / sampleRate
        if (bufferedSeconds < MIN_START_SECONDS) {
            return false
        }
        val speed = measuredSpeed() ?: return false

        val gapSeconds = longestGapNanos / NANOS_PER_SECOND
        if (speed >= 1.0) {
            return bufferedSeconds >= gapSeconds
        }
        val producedSeconds = produced.toDouble() / sampleRate
        val remainingSeconds = remainingSeconds(producedSeconds)
        val catchUpSeconds = remainingSeconds * (1.0 / speed - 1.0) * SAFETY_MARGIN
        return bufferedSeconds >= catchUpSeconds + gapSeconds
    }

    /**
     * Seconds of speech arriving a second, from what came after the first write; null until
     * [MEASURING_SECONDS] have passed between the first write and the last. Called with the lock held.
     */
    private fun measuredSpeed(): Double? {
        val elapsedSeconds = (lastWriteNanos - firstWriteNanos) / NANOS_PER_SECOND
        if (produced == 0L || elapsedSeconds < MEASURING_SECONDS) {
            return null
        }
        val arrivedSeconds = (produced - firstWriteSamples).toDouble() / sampleRate
        return arrivedSeconds / elapsedSeconds
    }

    /** How much of the answer is still to arrive. Called with the lock held. */
    private fun remainingSeconds(producedSeconds: Double): Double {
        val expected = expectedSamples
        if (expected == null) {
            return maxOf(ASSUMED_REMAINING_SECONDS, producedSeconds)
        }
        // The estimate may fall short: an answer that outgrew it may go on for a quarter as long again
        val expectedRemaining = (expected - produced).toDouble() / sampleRate
        val overrun = producedSeconds * OUTGROWN_FRACTION
        return maxOf(expectedRemaining, overrun, MIN_REMAINING_SECONDS)
    }

    companion object {
        /** Never start (or resume) on less, unless the answer is complete. */
        const val MIN_START_SECONDS = 0.5

        /** How long speech arrives, from the first write to the last, before its speed counts. */
        const val MEASURING_SECONDS = 0.5

        /** The least an answer of unknown length is taken to go on for. */
        const val ASSUMED_REMAINING_SECONDS = 8.0

        /** The least an answer of known length is taken to have still to come, until it is complete. */
        const val MIN_REMAINING_SECONDS = 1.0

        /** How much longer, as a share of what it has said, an answer that outgrew its expected length may go on. */
        const val OUTGROWN_FRACTION = 0.25

        /** Headroom for an arrival speed that drops as the answer goes on (a busy or warm phone). */
        const val SAFETY_MARGIN = 1.25

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private val EMPTY = FloatArray(0)
    }
}
