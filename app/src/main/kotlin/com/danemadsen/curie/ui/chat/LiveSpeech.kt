package com.danemadsen.curie.ui.chat

import com.danemadsen.curie.audio.SpeechOutput
import com.danemadsen.curie.inference.SpeechSink

/**
 * An answer's speech, played while the answer is generated. The output begins with the first
 * samples, so an answer that only calls tools takes no audio focus; when it is refused the audio
 * (another app keeps it) the rest is not played, though the answer still stores it.
 *
 * The sink is called on the engine's speech thread, the rest on the main thread.
 */
internal class LiveSpeech(private val output: SpeechOutput) {
    private enum class Stage {
        WAITING,
        PLAYING,
        REFUSED,
    }

    private var stage = Stage.WAITING

    val sink = SpeechSink { samples -> play(samples) }

    val hasStarted: Boolean
        get() = synchronized(this) { stage == Stage.PLAYING }

    val wasRefused: Boolean
        get() = synchronized(this) { stage == Stage.REFUSED }

    /** The answer is complete: what is buffered plays out. */
    fun finish() {
        if (hasStarted) {
            output.endOfAnswer()
        }
    }

    /** The answer was stopped or failed: its speech stops too. */
    fun cancel() {
        if (hasStarted) {
            output.stop()
        }
    }

    private fun play(samples: FloatArray) {
        synchronized(this) {
            if (stage == Stage.WAITING) {
                val isGranted = output.begin()
                stage = if (isGranted) Stage.PLAYING else Stage.REFUSED
            }
            if (stage == Stage.PLAYING) {
                output.write(samples)
            }
        }
    }
}
