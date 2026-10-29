package com.danemadsen.curie.audio

import kotlin.math.max
import kotlin.math.min

/** How sure the detector must be before it decides that the user speaks. */
enum class VadSensitivity {
    /** Waiting for the user: quiet speech counts. */
    NORMAL,

    /**
     * Curie is speaking: only clearly louder, longer speech counts, so her own voice coming back
     * through the microphone does not interrupt her.
     */
    BARGE_IN,
}

/** The tuning of a [VoiceActivityDetector]; durations in milliseconds, levels in dBFS. */
data class VadSettings(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,

    /** Speech this long starts an utterance... */
    val startSpeechMs: Int = 200,

    /** ...or this long while Curie speaks (a barge-in). */
    val bargeInSpeechMs: Int = 300,

    /** Silence this long ends an utterance. */
    val endSilenceMs: Int = 700,

    val maxUtteranceMs: Int = 30_000,

    /** Audio kept from before the speech was detected, so the first syllable is not cut off. */
    val preRollMs: Int = 300,

    /** Of the silence that ended an utterance, this much stays at its end. */
    val trailingSilenceMs: Int = 300,

    /** Pauses this long or shorter do not reset speech that has not started an utterance yet. */
    val maxStartGapMs: Int = 100,

    /** A frame is speech when it is this much louder than the background... */
    val speechMarginDb: Float = 10f,

    /** ...and at least this loud. */
    val minSpeechDb: Float = -50f,

    val bargeInMarginDb: Float = 18f,
    val bargeInMinSpeechDb: Float = -38f,

    /** The background level is the quietest frame of this window (minimum statistics). */
    val noiseWindowMs: Int = 3_000,
) {
    val frameSamples: Int
        get() = sampleRate * frameMs / 1000

    fun samplesOf(milliseconds: Int): Int = (sampleRate.toLong() * milliseconds / 1000).toInt()
}

sealed interface VadEvent {
    /** Speech started an utterance; [VoiceActivityDetector.isSpeaking] is true until it ends. */
    data object SpeechStarted : VadEvent

    /** The utterance ended: its samples, with the pre-roll before it and a little silence after it. */
    class SpeechEnded(val samples: FloatArray, val reason: EndReason) : VadEvent

    enum class EndReason {
        SILENCE,
        MAX_LENGTH,
    }
}

/**
 * An adaptive energy voice activity detector for 16 kHz mono audio.
 *
 * Each 20 ms frame is speech when its level stands [VadSettings.speechMarginDb] above the
 * background, which is the quietest frame of the last three seconds, and above an absolute floor.
 * Speech starts an utterance after [VadSettings.startSpeechMs] (short pauses allowed), and
 * [VadSettings.endSilenceMs] of silence or [VadSettings.maxUtteranceMs] ends it. The utterance
 * includes [VadSettings.preRollMs] of audio from before the speech was noticed.
 *
 * Pure and single-threaded: feed it chunks of any size with [process].
 */
class VoiceActivityDetector(private val settings: VadSettings = VadSettings()) {
    private enum class State {
        IDLE,
        CANDIDATE,
        SPEECH,
    }

    private val frameSamples = settings.frameSamples
    private val frame = FloatArray(frameSamples)
    private var frameFill = 0

    /** The most recent audio, for the pre-roll: a ring of two seconds plus the pre-roll. */
    private val history = FloatArray(settings.samplesOf(HISTORY_MS + settings.preRollMs))
    private var historyEnd = 0L

    private val noiseWindow: FloatArray
    private var noiseWindowFill = 0
    private var noiseWindowNext = 0

    private var state = State.IDLE
    private var candidateStart = 0L
    private var candidateSpeechMs = 0
    private var candidateGapMs = 0
    private var silenceMs = 0
    private var utterance = SampleBuffer()

    var sensitivity: VadSensitivity = VadSensitivity.NORMAL

    /** The meter reading (0 to 1) of the last frame. */
    var level: Float = 0f
        private set

    /** True from [VadEvent.SpeechStarted] until the utterance ends. */
    val isSpeaking: Boolean
        get() = state == State.SPEECH

    init {
        require(frameSamples > 0) { "a frame must hold samples" }
        val windowFrames = max(1, settings.noiseWindowMs / settings.frameMs)
        noiseWindow = FloatArray(windowFrames)
    }

    /** Feeds [samples]; returns what they started or ended, in order. */
    fun process(samples: FloatArray): List<VadEvent> {
        val events = ArrayList<VadEvent>(1)
        var offset = 0
        while (offset < samples.size) {
            val count = min(frameSamples - frameFill, samples.size - offset)
            samples.copyInto(frame, destinationOffset = frameFill, startIndex = offset, endIndex = offset + count)
            frameFill += count
            offset += count

            if (frameFill == frameSamples) {
                processFrame(events)
                frameFill = 0
            }
        }
        return events
    }

    /** Forgets the utterance in progress (the background estimate is kept). */
    fun reset() {
        state = State.IDLE
        utterance = SampleBuffer()
        candidateSpeechMs = 0
        candidateGapMs = 0
        silenceMs = 0
        frameFill = 0
    }

    private fun processFrame(events: MutableList<VadEvent>) {
        remember(frame)

        val rms = AudioLevels.rms(frame)
        val decibels = AudioLevels.decibels(rms)
        level = AudioLevels.meter(rms)
        val background = trackBackground(decibels)
        val isSpeech = isSpeech(decibels, background)

        when (state) {
            State.IDLE -> {
                if (isSpeech) {
                    state = State.CANDIDATE
                    candidateStart = historyEnd - frameSamples
                    candidateSpeechMs = settings.frameMs
                    candidateGapMs = 0
                    startIfLongEnough(events)
                }
            }
            State.CANDIDATE -> {
                if (isSpeech) {
                    candidateSpeechMs += settings.frameMs
                    candidateGapMs = 0
                    startIfLongEnough(events)
                } else {
                    candidateGapMs += settings.frameMs
                    if (candidateGapMs > settings.maxStartGapMs) {
                        state = State.IDLE
                    }
                }
            }
            State.SPEECH -> {
                utterance.append(frame, frameSamples)
                silenceMs = if (isSpeech) 0 else silenceMs + settings.frameMs
                endIfDone(events)
            }
        }
    }

    private fun startIfLongEnough(events: MutableList<VadEvent>) {
        val requiredMs = when (sensitivity) {
            VadSensitivity.NORMAL -> settings.startSpeechMs
            VadSensitivity.BARGE_IN -> settings.bargeInSpeechMs
        }
        if (candidateSpeechMs < requiredMs) {
            return
        }

        state = State.SPEECH
        silenceMs = 0
        val preRollStart = candidateStart - settings.samplesOf(settings.preRollMs)
        val oldestKept = historyEnd - history.size
        val start = max(max(preRollStart, oldestKept), 0L)
        utterance = SampleBuffer()
        copyHistory(start, utterance)
        events.add(VadEvent.SpeechStarted)
    }

    private fun endIfDone(events: MutableList<VadEvent>) {
        val maxSamples = settings.samplesOf(settings.maxUtteranceMs)
        if (silenceMs >= settings.endSilenceMs) {
            val trailingToDrop = settings.samplesOf(silenceMs - settings.trailingSilenceMs)
            val keptLength = max(0, utterance.size - trailingToDrop)
            val samples = utterance.toArray(keptLength)
            finishUtterance(events, VadEvent.SpeechEnded(samples, VadEvent.EndReason.SILENCE))
        } else if (utterance.size >= maxSamples) {
            val samples = utterance.toArray(maxSamples)
            finishUtterance(events, VadEvent.SpeechEnded(samples, VadEvent.EndReason.MAX_LENGTH))
        }
    }

    private fun finishUtterance(events: MutableList<VadEvent>, event: VadEvent.SpeechEnded) {
        events.add(event)
        state = State.IDLE
        utterance = SampleBuffer()
        silenceMs = 0
    }

    private fun isSpeech(decibels: Float, background: Float): Boolean {
        val threshold = when (sensitivity) {
            VadSensitivity.NORMAL -> max(background + settings.speechMarginDb, settings.minSpeechDb)
            VadSensitivity.BARGE_IN -> max(background + settings.bargeInMarginDb, settings.bargeInMinSpeechDb)
        }
        return decibels >= threshold
    }

    /** Adds [decibels] to the window and returns the window's quietest frame. */
    private fun trackBackground(decibels: Float): Float {
        noiseWindow[noiseWindowNext] = decibels
        noiseWindowNext = (noiseWindowNext + 1) % noiseWindow.size
        noiseWindowFill = min(noiseWindowFill + 1, noiseWindow.size)

        var quietest = Float.MAX_VALUE
        for (index in 0 until noiseWindowFill) {
            quietest = min(quietest, noiseWindow[index])
        }
        return quietest
    }

    private fun remember(samples: FloatArray) {
        for (sample in samples) {
            val index = Math.floorMod(historyEnd, history.size.toLong()).toInt()
            history[index] = sample
            historyEnd++
        }
    }

    private fun copyHistory(start: Long, target: SampleBuffer) {
        var position = start
        while (position < historyEnd) {
            val index = Math.floorMod(position, history.size.toLong()).toInt()
            target.append(history[index])
            position++
        }
    }

    private companion object {
        const val HISTORY_MS = 2_000
    }
}

/** A growable float array. */
internal class SampleBuffer(initialCapacity: Int = 16_000) {
    private var samples = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    fun append(sample: Float) {
        ensureCapacity(size + 1)
        samples[size] = sample
        size++
    }

    fun append(source: FloatArray, count: Int = source.size) {
        ensureCapacity(size + count)
        source.copyInto(samples, destinationOffset = size, startIndex = 0, endIndex = count)
        size += count
    }

    fun toArray(length: Int = size): FloatArray = samples.copyOf(min(length, size))

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= samples.size) {
            return
        }
        val grown = max(required, samples.size * 2)
        samples = samples.copyOf(grown)
    }
}
