package com.danemadsen.curie.assistant

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognitionService
import com.danemadsen.curie.audio.MicrophoneArbiter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/** The persisted switch, in memory. */
class FakeWakeWordPreferences(initiallyEnabled: Boolean = false) : WakeWordPreferences {
    val state = MutableStateFlow(initiallyEnabled)

    override val enabled: Flow<Boolean> = state

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
    }
}

/** Plays the app's part: owns the one WakeWordController the system-created components look up. */
class TestAssistantApplication : Application(), WakeWordControllerOwner {
    val preferences = FakeWakeWordPreferences()
    val microphone = MicrophoneArbiter()

    override val wakeWordController: WakeWordController by lazy {
        WakeWordController(this, preferences, microphone)
    }
}

/** Installs a speech recognition service, so SpeechRecognizer.isRecognitionAvailable() is true. */
fun installRecognitionService(context: Context) {
    val component = ComponentName("com.example.speech", "com.example.speech.RecognizerService")
    val packageManager = shadowOf(context.packageManager)
    packageManager.addServiceIfNotPresent(component)

    val filter = IntentFilter(RecognitionService.SERVICE_INTERFACE)
    packageManager.addIntentFilterForService(component, filter)
}

/** The CurieVoiceInteractionService component, as VoiceInteractionService.isActiveService compares it. */
fun assistantComponent(context: Context): ComponentName {
    return ComponentName(context, CurieVoiceInteractionService::class.java)
}

/** Adds an exit record of this app's process; Android lists them newest first, as this shadow does. */
fun recordProcessExit(context: Context, reason: Int, timestampMillis: Long) {
    val builder = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
    builder.setProcessName(context.packageName)
    builder.setReason(reason)
    builder.setTimestamp(timestampMillis)
    val exit = builder.build()

    val activityManager = context.getSystemService(ActivityManager::class.java)
    shadowOf(activityManager).addApplicationExitInfo(exit)
}

fun Intent.triggerExtra(): String? {
    return getStringExtra(com.danemadsen.curie.call.CallLauncher.EXTRA_TRIGGER)
}
