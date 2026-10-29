package com.danemadsen.curie.assistant

/** The values of CallLauncher.EXTRA_TRIGGER the assistant components send. Informational only. */
object CallTriggers {
    /** The assist gesture, the power button or the lock screen. */
    const val ASSIST = "assist"

    const val WAKE_WORD = "wake_word"

    /** The user tapped the "tap to talk" notification. */
    const val NOTIFICATION = "notification"
}
