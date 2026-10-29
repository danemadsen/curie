package com.danemadsen.curie.assistant

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Exists only because a voice interaction service must name a recognitionService. From Android 12,
 * the only versions where Curie's voice interaction service is enabled, the attribute does nothing.
 * The service is not exported and has no intent filter, so no app (Curie included) can reach it and
 * it never becomes the default recognizer; should anything start it anyway, it fails at once.
 */
class CurieRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (exception: RemoteException) {
            Log.w(TAG, "The client is gone", exception)
        }
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit

    private companion object {
        const val TAG = "CurieRecognition"
    }
}
