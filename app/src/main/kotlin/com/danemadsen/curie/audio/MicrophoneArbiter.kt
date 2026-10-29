package com.danemadsen.curie.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Decides who records from the microphone. Calls and voice messages take it over from the "Hey Curie"
 * listener, which watches [owner], stops its recognizer when it loses the microphone and asks for it
 * again once [owner] is null.
 */
class MicrophoneArbiter {
    enum class Owner {
        WAKE_WORD,
        CALL,
        VOICE_MESSAGE,
    }

    private val currentOwner = MutableStateFlow<Owner?>(null)

    val owner: StateFlow<Owner?> = currentOwner.asStateFlow()

    /**
     * Gives the microphone to [requester]. The wake word listener only gets a free microphone; calls
     * and voice messages pre-empt it, but not each other. Returns whether [requester] holds it now.
     */
    fun acquire(requester: Owner): Boolean {
        var granted = false
        currentOwner.update { holder ->
            val available = holder == null || holder == requester
            val preempts = holder == Owner.WAKE_WORD && requester != Owner.WAKE_WORD
            granted = available || preempts
            if (granted) requester else holder
        }
        return granted
    }

    /** Frees the microphone if [holder] has it. */
    fun release(holder: Owner) {
        currentOwner.update { current -> if (current == holder) null else current }
    }
}
