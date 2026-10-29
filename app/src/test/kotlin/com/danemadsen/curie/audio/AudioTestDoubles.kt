package com.danemadsen.curie.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** A microphone that hears what a test sends it. */
class FakeMicrophone : MicrophoneSource {
    val chunks = Channel<FloatArray>(Channel.UNLIMITED)
    var purpose: RecordingPurpose? = null
        private set

    override fun record(purpose: RecordingPurpose): Flow<FloatArray> {
        this.purpose = purpose
        return chunks.consumeAsFlow()
    }

    /**
     * [durationMs] of speech-like sound, in 20 ms chunks: 200 ms syllables of a loud tone with 60 ms
     * dips between them. (A steady tone would read as background noise, as it should.)
     */
    fun say(durationMs: Int) {
        val chunkCount = durationMs / AudioRecorder.CHUNK_MS
        for (chunk in 0 until chunkCount) {
            val positionMs = chunk * AudioRecorder.CHUNK_MS % SYLLABLE_PERIOD_MS
            val amplitude = if (positionMs < SYLLABLE_MS) SPEECH_AMPLITUDE else DIP_AMPLITUDE
            chunks.trySend(tone(chunk, amplitude))
        }
    }

    fun beSilent(durationMs: Int) {
        repeat(durationMs / AudioRecorder.CHUNK_MS) { chunk ->
            chunks.trySend(tone(chunk, amplitude = 0f))
        }
    }

    private fun tone(chunk: Int, amplitude: Float): FloatArray {
        val chunkSamples = AudioRecorder.SAMPLE_RATE * AudioRecorder.CHUNK_MS / 1000
        return FloatArray(chunkSamples) { index ->
            val time = (chunk * chunkSamples + index).toFloat() / AudioRecorder.SAMPLE_RATE
            amplitude * sin(2f * PI.toFloat() * TONE_HZ * time)
        }
    }

    private companion object {
        const val SYLLABLE_MS = 200
        const val SYLLABLE_PERIOD_MS = 260
        const val SPEECH_AMPLITUDE = 0.3f
        const val DIP_AMPLITUDE = 0.001f
        const val TONE_HZ = 220f
    }
}

/**
 * A speaker that records what it is given. It plays as soon as an answer has samples; an answer
 * ends when the test calls [finishPlaying] (or at once after [endOfAnswer] with [finishesByItself]).
 * With [refusesToPlay] it acts as if another app kept the audio focus.
 */
class FakeSpeechOutput(var finishesByItself: Boolean = false) : SpeechOutput {
    private val playing = MutableStateFlow(false)
    private var finished: CompletableDeferred<Boolean>? = null

    var refusesToPlay = false

    var answers = 0
        private set
    var samplesWritten = 0
        private set
    var stops = 0
        private set
    var isPaused = false
        private set
    var speakerOn = true
        private set
    var isAnswerComplete = false
        private set

    override val level: Float
        get() = if (playing.value) 0.5f else 0f

    override val isPlaying: StateFlow<Boolean> = playing

    override fun begin(): Boolean {
        finished?.complete(false)
        if (refusesToPlay) {
            finished = null
            return false
        }
        finished = CompletableDeferred()
        answers++
        isAnswerComplete = false
        return true
    }

    override fun write(samples: FloatArray) {
        samplesWritten += samples.size
        playing.value = true
    }

    /** What the last answer was expected to last, when it was told. */
    var expectedSeconds: Double? = null
        private set

    override fun expectDuration(seconds: Double) {
        expectedSeconds = seconds
    }

    override fun endOfAnswer() {
        isAnswerComplete = true
        if (finishesByItself) {
            finishPlaying()
        }
    }

    /** The answer has played to its end. */
    fun finishPlaying() {
        playing.value = false
        finished?.complete(true)
    }

    override suspend fun awaitFinished(): Boolean {
        val current = finished ?: return true
        return current.await()
    }

    override fun stop() {
        stops++
        playing.value = false
        finished?.complete(false)
        finished = null
    }

    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        this.speakerOn = speakerOn
    }

    override fun release() {
        stop()
    }
}

/** Clip playback that remembers what it was asked to play; with [refusesToPlay] another app keeps the audio. */
class FakeClipPlayback : ClipPlayback {
    private val current = MutableStateFlow<ClipPlaybackState?>(null)
    val played = ArrayList<Pair<String, File>>()

    var refusesToPlay = false

    override val state: StateFlow<ClipPlaybackState?> = current

    override fun toggle(clipId: String, file: File): Boolean {
        val playing = current.value
        if (playing == null || playing.clipId != clipId) {
            return play(clipId, file)
        }
        if (!playing.isPlaying && refusesToPlay) {
            return false
        }
        current.value = playing.copy(isPlaying = !playing.isPlaying)
        return true
    }

    override fun play(clipId: String, file: File): Boolean {
        if (refusesToPlay) {
            return false
        }
        played.add(clipId to file)
        current.value = ClipPlaybackState(clipId, isPlaying = true, positionMs = 0)
        return true
    }

    override fun stop() {
        current.value = null
    }

    override fun release() {
        stop()
    }
}
