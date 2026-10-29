package com.danemadsen.curie.assistant

/** What the "Hey Curie" listener is doing, for the settings screen. Reasons are user-facing text. */
sealed interface WakeWordStatus {
    /** The user turned "Hey Curie" off. */
    data object Disabled : WakeWordStatus

    /** "Hey Curie" is on but the microphone permission is not granted. */
    data object MissingPermission : WakeWordStatus

    data object Listening : WakeWordStatus

    /** On, but not listening right now, for example during a call or with Battery Saver on. */
    data class Paused(val reason: String) : WakeWordStatus

    /** This phone cannot listen for "Hey Curie", for example without a speech recognition service. */
    data class Unavailable(val reason: String) : WakeWordStatus
}
