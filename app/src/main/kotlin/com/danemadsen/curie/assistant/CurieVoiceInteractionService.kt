package com.danemadsen.curie.assistant

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.danemadsen.curie.call.CallLauncher

/**
 * Curie's voice interaction service: what the system binds while Curie is the default digital
 * assistant (Android 12+; earlier versions use the CallActivity's ACTION_ASSIST filter). The system
 * keeps it running, which also keeps "Hey Curie" listening, and its binding lets Curie open the call
 * screen and use the microphone from the background.
 */
class CurieVoiceInteractionService : VoiceInteractionService() {
    private var isReady = false
    private var isDestroyed = false

    override fun onReady() {
        super.onReady()

        // The documentation warns that onReady() can arrive after onDestroy().
        if (isDestroyed) {
            return
        }

        isReady = true
        ActiveAssistant.attach(this)

        // The binding exempts this start from the background limits, also right after a reboot.
        val controller = WakeWordControllerOwner.controller(this)
        controller?.onAssistantReady()
    }

    override fun onShutdown() {
        isReady = false
        ActiveAssistant.detach(this)
        super.onShutdown()
    }

    override fun onDestroy() {
        isDestroyed = true
        isReady = false
        ActiveAssistant.detach(this)
        super.onDestroy()
    }

    /** The voice assist shortcut on the lock screen: the call screen shows over the keyguard. */
    override fun onLaunchVoiceAssistFromKeyguard() {
        val callIntent = CallLauncher.intent(this, CallTriggers.ASSIST)
        try {
            startActivity(callIntent)
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "The call screen is missing", exception)
        }
    }

    /**
     * Opens a conversation the way the assist gesture does (the session starts the call screen).
     * False when the system would refuse, for example before onReady().
     */
    fun showConversation(trigger: String): Boolean {
        if (!isReady) {
            return false
        }

        val sessionArguments = Bundle()
        sessionArguments.putString(CallLauncher.EXTRA_TRIGGER, trigger)
        return try {
            // 0: no screenshot and no screen content are requested.
            showSession(sessionArguments, 0)
            true
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "The system refused to show a session", exception)
            false
        } catch (exception: SecurityException) {
            Log.w(TAG, "Curie is no longer the active assistant", exception)
            false
        }
    }

    private companion object {
        const val TAG = "CurieAssistant"
    }
}
