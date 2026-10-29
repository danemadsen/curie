package com.danemadsen.curie.assistant.wakeword

import com.danemadsen.curie.audio.MicrophoneArbiter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the engine reports to the service that runs it, on top of the loop's states. Main thread. */
internal interface WakeWordEngineListener : RecognitionLoopListener {
    /**
     * Opens the conversation; the microphone and the recognizer are already released. False when
     * nothing could show, which stops listening: hearing "Hey Curie" again would do nothing either.
     */
    fun onWakeWord(): Boolean

    /** Listening cannot continue; the engine waits to be cancelled. */
    fun onStopped(reason: StopReason)
}

/**
 * Listens only while it is reasonable: not too hot, no Battery Saver, battery not low, and the
 * microphone not taken by Curie's own call or voice message ([MicrophoneArbiter]). Whenever one of
 * these changes, the running recognition is cancelled (which releases the recognizer and the
 * microphone) and listening starts again once all of them allow it.
 */
internal class WakeWordEngine(
    private val conditions: Flow<DeviceConditions>,
    private val microphone: MicrophoneArbiter,
    private val recognition: Recognition,
    private val listener: WakeWordEngineListener,
) {
    private sealed interface Blocker {
        data class Device(val reason: PauseReason) : Blocker

        data class Microphone(val owner: MicrophoneArbiter.Owner) : Blocker
    }

    /** Runs until cancelled; call it on the main thread. */
    suspend fun run() {
        val blockers = combine(conditions, microphone.owner) { deviceConditions, owner ->
            blockerFor(deviceConditions, owner)
        }
        val blockerChanges = blockers.distinctUntilChanged()

        var previousBlocker: Blocker? = null
        blockerChanges.collectLatest { blocker ->
            val microphoneWasTaken = previousBlocker is Blocker.Microphone
            previousBlocker = blocker

            if (blocker != null) {
                listener.onPaused(pauseReasonOf(blocker))
                return@collectLatest
            }

            // Let the call or voice message settle (and Curie stop talking) before listening again.
            if (microphoneWasTaken) {
                delay(MICROPHONE_RETURN_DELAY_MILLIS)
            }
            listenWhileAllowed()
        }
    }

    private suspend fun listenWhileAllowed() {
        while (true) {
            // Someone else may have taken the microphone just now; the owner flow then pauses listening.
            val hasMicrophone = microphone.acquire(MicrophoneArbiter.Owner.WAKE_WORD)
            if (!hasMicrophone) {
                return
            }

            val result = try {
                recognition.run()
            } finally {
                microphone.release(MicrophoneArbiter.Owner.WAKE_WORD)
            }

            when (result) {
                ListenResult.WakeWordHeard -> {
                    val isOpening = listener.onWakeWord()
                    if (!isOpening) {
                        listener.onStopped(StopReason.CANNOT_OPEN_CONVERSATION)
                        awaitCancellation()
                    }

                    // Gives the call time to take the microphone, and skips the rest of the phrase.
                    delay(AFTER_WAKE_WORD_DELAY_MILLIS)
                }

                is ListenResult.Stopped -> {
                    listener.onStopped(result.reason)
                    awaitCancellation()
                }
            }
        }
    }

    private fun blockerFor(deviceConditions: DeviceConditions, owner: MicrophoneArbiter.Owner?): Blocker? {
        val pauseReason = deviceConditions.pauseReason()
        if (pauseReason != null) {
            return Blocker.Device(pauseReason)
        }

        // Free, or held by this listener itself.
        if (owner == null || owner == MicrophoneArbiter.Owner.WAKE_WORD) {
            return null
        }
        return Blocker.Microphone(owner)
    }

    private fun pauseReasonOf(blocker: Blocker): PauseReason {
        return when (blocker) {
            is Blocker.Device -> blocker.reason
            is Blocker.Microphone -> PauseReason.MICROPHONE_IN_USE
        }
    }

    companion object {
        /** How long after Curie's own call or voice message frees the microphone listening resumes. */
        const val MICROPHONE_RETURN_DELAY_MILLIS = 750L

        const val AFTER_WAKE_WORD_DELAY_MILLIS = 3_000L
    }
}
