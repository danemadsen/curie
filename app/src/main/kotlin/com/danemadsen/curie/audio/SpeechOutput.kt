package com.danemadsen.curie.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Where the spoken answers of a call stream to, answer by answer: [begin], any number of
 * [write]s as the decoder produces samples, then [endOfAnswer].
 */
interface SpeechOutput {
    /** The 0 to 1 level of what is playing now; cheap to read on every frame. */
    val level: Float

    /** True while speech is audibly playing, false while it buffers, pauses or is done. */
    val isPlaying: StateFlow<Boolean>

    /**
     * Starts a new answer, dropping anything left of the previous one. False when it may not play
     * now: an output that takes the audio focus itself was refused it (another app keeps the audio,
     * such as a phone call), and nothing will play.
     */
    fun begin(): Boolean

    fun write(samples: FloatArray)

    /** No more samples come for this answer: what is buffered plays out. */
    fun endOfAnswer()

    /**
     * Suspends until the answer has played to its end (true) or was stopped (false). Returns at
     * once when no answer is under way.
     */
    suspend fun awaitFinished(): Boolean

    /** Stops the answer at once and drops what was queued ([awaitFinished] then returns false). */
    fun stop()

    /** Pauses or resumes playback without dropping anything (another app took the audio). */
    fun setPaused(paused: Boolean)

    /** Plays through the loudspeaker, or else through the earpiece where the phone has one. */
    fun setSpeakerOn(speakerOn: Boolean)

    fun release()
}
