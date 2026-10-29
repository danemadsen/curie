package com.danemadsen.curie.assistant

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether an activity Curie starts now would actually appear. Android drops background activity
 * starts silently, so callers check this before they tell the user that something opened.
 */
internal fun interface ActivityStartPolicy {
    fun canStartActivities(): Boolean
}

/** Curie may start activities while one of its windows is visible or while it is the active assistant. */
internal class VisibleOrAssistantPolicy(private val context: Context) : ActivityStartPolicy {
    override fun canStartActivities(): Boolean {
        val processState = ProcessLifecycleOwner.get().lifecycle.currentState
        val isVisible = processState.isAtLeast(Lifecycle.State.STARTED)
        if (isVisible) {
            return true
        }

        // The system binds the active voice interaction service with BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
        // which lets every Curie process start activities; holding the assistant role alone does not.
        return AssistantRole.isActiveVoiceInteractionService(context)
    }
}
