package com.danemadsen.curie.assistant.wakeword

import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.danemadsen.curie.assistant.ActiveAssistant
import com.danemadsen.curie.assistant.ActivityStartPolicy
import com.danemadsen.curie.assistant.AssistantRole
import com.danemadsen.curie.assistant.CallTriggers
import com.danemadsen.curie.call.CallLauncher

/** How the call screen was brought up after "Hey Curie". */
internal enum class Handoff {
    /** As the active assistant: the system shows a session, which starts the call screen. */
    ASSISTANT_SESSION,

    /** Started directly: a visible Curie window, the assistant binding or "Display over other apps" allows it. */
    DIRECT_START,

    /** None of these: a heads-up notification lets the user tap to talk. */
    NOTIFICATION,

    /** Nothing could show, not even the notification (notifications are blocked). */
    NONE,
}

/**
 * Brings up the call screen after the wake word, using the strongest permission Curie has: being the
 * active voice interaction service, then a visible window or the overlay permission, and otherwise a
 * notification (a background activity start without an exemption would be dropped silently).
 */
internal class WakeWordHandoff(
    private val context: Context,
    private val notifications: WakeWordNotifications,
    private val startPolicy: ActivityStartPolicy,
) {
    /**
     * Whether "Hey Curie" heard while Curie is in the background can bring up the call screen: as the
     * active assistant, with "Display over other apps", or through the "tap to talk" notification.
     */
    fun canOpenFromBackground(): Boolean {
        if (AssistantRole.isActiveVoiceInteractionService(context)) {
            return true
        }
        if (Settings.canDrawOverlays(context)) {
            return true
        }
        return notifications.canShowTapToTalk()
    }

    fun openConversation(): Handoff {
        val assistant = ActiveAssistant.current()
        if (assistant != null && AssistantRole.isActiveVoiceInteractionService(context)) {
            val shown = assistant.showConversation(CallTriggers.WAKE_WORD)
            if (shown) {
                return Handoff.ASSISTANT_SESSION
            }
        }

        val mayStartActivities = startPolicy.canStartActivities() || Settings.canDrawOverlays(context)
        if (mayStartActivities && startCallScreen()) {
            return Handoff.DIRECT_START
        }

        val isTapToTalkShown = notifications.showTapToTalk()
        if (isTapToTalkShown) {
            return Handoff.NOTIFICATION
        }
        return Handoff.NONE
    }

    private fun startCallScreen(): Boolean {
        val callIntent = CallLauncher.intent(context, CallTriggers.WAKE_WORD)
        return try {
            context.startActivity(callIntent)
            true
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "The call screen is missing", exception)
            false
        }
    }

    private companion object {
        const val TAG = "WakeWordHandoff"
    }
}
