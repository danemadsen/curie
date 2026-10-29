package com.danemadsen.curie.call

import android.content.Context
import android.content.Intent

/**
 * Starts a call with Curie: the CallActivity answers [ACTION_START_CALL] inside this package, so the
 * header phone button, the assistant session, the wake word and notifications all share one entry
 * point without depending on the activity class.
 */
object CallLauncher {
    const val ACTION_START_CALL = "com.danemadsen.curie.action.START_CALL"

    /** Why the call started: "button", "assist", "wake_word" or "notification". Informational only. */
    const val EXTRA_TRIGGER = "com.danemadsen.curie.extra.TRIGGER"

    fun intent(context: Context, trigger: String): Intent {
        val intent = Intent(ACTION_START_CALL)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_TRIGGER, trigger)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
