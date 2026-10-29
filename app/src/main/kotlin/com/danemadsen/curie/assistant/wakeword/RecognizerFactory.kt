package com.danemadsen.curie.assistant.wakeword

import android.content.Context
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A SpeechRecognizer and whether it is the on-device one. */
internal class ActiveRecognizer(
    val speechRecognizer: SpeechRecognizer,
    val isOnDevice: Boolean,
) {
    /** Must be called for every recognizer: a leaked one keeps its system connection. */
    fun destroy() {
        speechRecognizer.destroy()
    }
}

internal fun interface RecognizerFactory {
    /** A new recognizer, or null when the device has none. Main thread only. */
    @MainThread
    suspend fun create(preferOnDevice: Boolean): ActiveRecognizer?
}

/**
 * The on-device recognizer when it is available and has English (US) installed (API 33+, where its
 * language support can be checked), otherwise the user's default speech recognition service.
 * Curie's own recognition service is never the default (it is disabled below API 31 and declares
 * selectableAsDefault="false" from 31), so the default recognizer is always another app.
 */
internal class PlatformRecognizerFactory(private val context: Context) : RecognizerFactory {

    @MainThread
    override suspend fun create(preferOnDevice: Boolean): ActiveRecognizer? {
        if (preferOnDevice && mayUseOnDevice(context)) {
            val onDevice = createOnDeviceWithLanguage()
            if (onDevice != null) {
                return onDevice
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return null
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        return ActiveRecognizer(recognizer, isOnDevice = false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun createOnDeviceWithLanguage(): ActiveRecognizer? {
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (exception: UnsupportedOperationException) {
            Log.w(TAG, "The on-device recognizer is unavailable", exception)
            return null
        }

        val hasLanguage = try {
            withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MILLIS) {
                hasWakeLanguageInstalled(recognizer)
            }
        } catch (cancellation: CancellationException) {
            recognizer.destroy()
            throw cancellation
        }
        if (hasLanguage == true) {
            return ActiveRecognizer(recognizer, isOnDevice = true)
        }

        // One recognizer at a time: this one goes before the default one is created.
        recognizer.destroy()
        return null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun hasWakeLanguageInstalled(recognizer: SpeechRecognizer): Boolean {
        val probeIntent = RecognizerIntents.build(audioSource = null)
        return suspendCancellableCoroutine { continuation ->
            val callback = object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    val installedLanguages = recognitionSupport.installedOnDeviceLanguages
                    val isInstalled = installedLanguages.any { language -> RecognizerIntents.isWakeLanguage(language) }
                    if (continuation.isActive) {
                        continuation.resume(isInstalled)
                    }
                }

                override fun onError(error: Int) {
                    // ERROR_CANNOT_CHECK_SUPPORT on some services: fall back to the default recognizer.
                    Log.d(TAG, "The on-device language check failed with $error")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
            recognizer.checkRecognitionSupport(probeIntent, context.mainExecutor, callback)
        }
    }

    companion object {
        private const val TAG = "WakeWordRecognizer"
        private const val SUPPORT_CHECK_TIMEOUT_MILLIS = 3_000L

        /**
         * Whether listening may use the on-device recognizer: Android 13+, where its installed languages
         * can be checked, on a phone that has one. The system picks that service, so it can be another
         * app than the user's default speech recognition service.
         */
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
        fun mayUseOnDevice(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return false
            }
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }
    }
}
