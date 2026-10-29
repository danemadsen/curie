package com.danemadsen.curie.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.danemadsen.curie.call.CallLauncher

/** Creates the session the system shows for the assist gesture, the power button or "Hey Curie". */
class CurieVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CurieVoiceInteractionSession(this)
    }
}

/**
 * A session without a window of its own: it starts the call screen as the assistant activity (which
 * the system shows above other apps and allows from the background) and hides itself.
 */
internal class CurieVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Null before Android 14; the system's own invocations carry no trigger of Curie's.
        val requestedTrigger = args?.getString(CallLauncher.EXTRA_TRIGGER)
        val trigger = requestedTrigger ?: CallTriggers.ASSIST
        val callIntent = CallLauncher.intent(context, trigger)

        try {
            // Only allowed while the session is shown, which it is in onShow.
            startAssistantActivity(callIntent)
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "The session was hidden before the call screen could start", exception)
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "The call screen is missing", exception)
        } catch (exception: SecurityException) {
            Log.e(TAG, "The call screen could not be started", exception)
        }

        // hide() does not finish the assistant activity; it only ends this session.
        hide()
    }

    private companion object {
        const val TAG = "CurieSession"
    }
}
