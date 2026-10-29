package com.danemadsen.curie.ui.call

import androidx.compose.runtime.Immutable
import com.danemadsen.curie.ui.common.Persona

/** What the assistant is doing in the call. */
enum class CallPhase {
    /** The engine and the microphone are starting. */
    CONNECTING,

    /** Waiting for the user to speak, or hearing them. */
    LISTENING,

    /** The user stopped talking and the answer is being prepared. */
    THINKING,

    /** The answer is being spoken. */
    SPEAKING,

    /** The call is over; the screen closes. */
    ENDED,
}

/**
 * Everything the call screen shows. The audio level that makes the corona flare is passed
 * separately (see [CallScreen]) because it changes every frame.
 */
@Immutable
data class CallUiState(
    val assistant: Persona,
    val phase: CallPhase,

    /** Time since the call connected, in seconds. */
    val elapsedSeconds: Long = 0L,

    /** What the user said last, as transcribed; blank before they speak. */
    val userCaption: String = "",

    /** The user is talking right now (while [phase] is [CallPhase.LISTENING]). */
    val isUserSpeaking: Boolean = false,

    /** The text of the answer being spoken; blank between answers. */
    val assistantCaption: String = "",

    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
)

/** What the user asks for during a call. */
sealed interface CallAction {
    data object ToggleMute : CallAction

    data object ToggleSpeaker : CallAction

    data object EndCall : CallAction
}
