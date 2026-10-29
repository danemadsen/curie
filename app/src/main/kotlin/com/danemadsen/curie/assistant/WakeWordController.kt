package com.danemadsen.curie.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.wakeword.PauseReason
import com.danemadsen.curie.assistant.wakeword.PlatformRecognizerFactory
import com.danemadsen.curie.assistant.wakeword.StopReason
import com.danemadsen.curie.assistant.wakeword.UserStopHold
import com.danemadsen.curie.assistant.wakeword.WakeWordHandoff
import com.danemadsen.curie.assistant.wakeword.WakeWordNotifications
import com.danemadsen.curie.assistant.wakeword.WakeWordService
import com.danemadsen.curie.audio.MicrophoneArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The "Hey Curie" switch and its state. One instance lives as long as the app (it owns a main-thread
 * scope for that reason) and is shared with the system-created components through
 * [WakeWordControllerOwner].
 *
 * Listening runs in a microphone foreground service that may only start from a visible activity
 * ([setEnabled], [onAppStart]), a notification tap, or the active voice interaction service; never
 * from the background or at boot, where Android would refuse it microphone access. When Android
 * stops it, a "Tap to resume" notification brings it back.
 *
 * Nothing listens unless hearing "Hey Curie" in another app could bring Curie up: as the active
 * assistant, with "Display over other apps", or through a notification.
 */
class WakeWordController(
    context: Context,
    private val preferences: WakeWordPreferences,
    private val microphone: MicrophoneArbiter,
) {
    private val appContext = context.applicationContext
    private val notifications = WakeWordNotifications(appContext)
    private val startPolicy = VisibleOrAssistantPolicy(appContext)
    private val handoff = WakeWordHandoff(appContext, notifications, startPolicy)
    private val userStopHold = UserStopHold(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow<WakeWordStatus>(WakeWordStatus.Disabled)

    @Volatile
    private var isServiceRunning = false

    @Volatile
    private var isServiceRequested = false

    val status: StateFlow<WakeWordStatus> = mutableStatus.asStateFlow()

    /**
     * Below Android 13 the recognizer opens the microphone itself and restarts every few seconds,
     * which can beep: the settings screen should label the switch as experimental.
     */
    val isExperimental: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Request these right after showing the [disclosure] (Play's prominent disclosure) and before
     * calling setEnabled(true). Notifications are needed for the listening notice and "tap to talk".
     */
    val requiredPermissions: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        listOf(Manifest.permission.RECORD_AUDIO)
    }

    init {
        notifications.createChannels()
        scope.launch { refreshStatus() }
    }

    /**
     * The prominent disclosure (Play User Data policy): show it as a dialog with its two buttons right
     * before the permission prompts; only the accept button counts as consent.
     */
    fun disclosure(): WakeWordDisclosure {
        val speechService = speechServiceLabel() ?: appContext.getString(R.string.wake_word_speech_service_generic)

        // Where listening prefers the on-device recognizer, which may be another app than the default
        // service, the text names both.
        val mayUseOnDevice = PlatformRecognizerFactory.mayUseOnDevice(appContext)
        val textId = if (mayUseOnDevice) R.string.wake_word_disclosure_on_device else R.string.wake_word_disclosure

        return WakeWordDisclosure(
            title = appContext.getString(R.string.wake_word_disclosure_title),
            text = appContext.getString(textId, speechService),
            acceptLabel = appContext.getString(R.string.wake_word_disclosure_accept),
            declineLabel = appContext.getString(R.string.wake_word_disclosure_decline),
        )
    }

    /** A note for the switch below Android 13 ([isExperimental]), else null. */
    fun experimentalNote(): String? {
        if (!isExperimental) {
            return null
        }
        return appContext.getString(R.string.wake_word_experimental)
    }

    /** Call from a visible activity, after the disclosure and the permission prompts. */
    suspend fun setEnabled(enabled: Boolean) {
        preferences.setEnabled(enabled)
        if (enabled) {
            startListening()
        } else {
            stopListening()
        }
    }

    /** Call when Curie's UI becomes visible: restarts listening that Android or a reboot stopped. */
    fun onAppStart() {
        scope.launch {
            val enabled = preferences.enabled.first()
            if (enabled) {
                startListening()
            } else {
                mutableStatus.value = WakeWordStatus.Disabled
            }
        }
    }

    /**
     * The voice interaction service is ready: its binding lets the microphone service start now.
     * Android also binds it right after the user stopped Curie; then nothing starts until the user
     * starts listening again ([UserStopHold]), and the status says to open Curie.
     */
    internal fun onAssistantReady() {
        scope.launch {
            val enabled = preferences.enabled.first()
            if (!enabled) {
                return@launch
            }

            if (userStopHold.isHolding()) {
                refreshStatus()
            } else {
                startListening()
            }
        }
    }

    /**
     * After a reboot or an app update. A microphone service must not start here; unless the voice
     * interaction service will start it, the user is offered a notification to resume.
     */
    internal fun onSystemStartup(): Job {
        return scope.launch {
            val enabled = preferences.enabled.first()
            val isActiveAssistant = AssistantRole.isActiveVoiceInteractionService(appContext)
            val assistantWillStart = isActiveAssistant && !userStopHold.isHolding()
            if (!enabled || assistantWillStart || isServiceRunning) {
                return@launch
            }
            showResumeNeeded()
        }
    }

    internal suspend fun isEnabled(): Boolean {
        return preferences.enabled.first()
    }

    internal fun hasMicrophonePermission(): Boolean {
        val state = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
        return state == PackageManager.PERMISSION_GRANTED
    }

    internal val microphoneArbiter: MicrophoneArbiter
        get() = microphone

    internal fun onServiceCreated() {
        isServiceRunning = true
    }

    /**
     * The listening service was started on request, not restarted by Android: by the user (the app,
     * the switch, the resume notification) or by the assistant while no user stop holds.
     */
    internal fun onRequestedStart() {
        userStopHold.lift()
    }

    internal fun onServiceDestroyed() {
        isServiceRunning = false
        isServiceRequested = false

        // Stopped by something other than the user or a definite failure (those set their own status).
        val current = mutableStatus.value
        val wasRunning = current == WakeWordStatus.Listening || current is WakeWordStatus.Paused
        if (wasRunning) {
            mutableStatus.value = waitingStatus()
        }
    }

    /** The service is about to start listening. */
    internal fun onStarting() {
        mutableStatus.value = pausedStatus(R.string.wake_word_status_starting)
    }

    internal fun onListening() {
        mutableStatus.value = WakeWordStatus.Listening
    }

    internal fun onPaused(reason: PauseReason) {
        val reasonText = when (reason) {
            PauseReason.TOO_HOT -> R.string.wake_word_paused_too_hot
            PauseReason.BATTERY_SAVER -> R.string.wake_word_paused_battery_saver
            PauseReason.LOW_BATTERY -> R.string.wake_word_paused_low_battery
            PauseReason.MICROPHONE_IN_USE -> R.string.wake_word_paused_microphone_in_use
            PauseReason.MICROPHONE_SILENCED -> R.string.wake_word_paused_silenced
        }
        mutableStatus.value = pausedStatus(reasonText)
    }

    /** Listening cannot continue; the service stops itself after this. */
    internal fun onListeningStopped(reason: StopReason) {
        mutableStatus.value = when (reason) {
            StopReason.NO_PERMISSION -> {
                if (hasMicrophonePermission()) {
                    // Granted, but not usable in the background: only a visible start brings it back.
                    notifications.showResumePrompt()
                    waitingStatus()
                } else {
                    WakeWordStatus.MissingPermission
                }
            }

            StopReason.LANGUAGE_NOT_SUPPORTED -> unavailableStatus(R.string.wake_word_unavailable_language)
            StopReason.NO_RECOGNIZER -> unavailableStatus(R.string.wake_word_unavailable_no_recognizer)

            // Notifications were blocked while Curie listened: the next start checks them again.
            StopReason.CANNOT_OPEN_CONVERSATION -> waitingStatus()
        }
    }

    /** Android refused the microphone service (a start from the background). */
    internal fun onStartRefused() {
        showResumeNeeded()
    }

    internal fun onMissingPermission() {
        mutableStatus.value = WakeWordStatus.MissingPermission
    }

    /** The service started but "Hey Curie" had been switched off in the meantime. */
    internal fun onFoundDisabled() {
        mutableStatus.value = WakeWordStatus.Disabled
    }

    /** The notification's "Turn off": the same as switching "Hey Curie" off in the settings. */
    internal fun turnOffFromNotification() {
        scope.launch {
            preferences.setEnabled(false)
            notifications.cancelResumePrompt()
            mutableStatus.value = WakeWordStatus.Disabled
        }
    }

    private fun startListening() {
        if (!hasMicrophonePermission()) {
            mutableStatus.value = WakeWordStatus.MissingPermission
            return
        }
        if (!isRecognitionAvailable()) {
            mutableStatus.value = unavailableStatus(R.string.wake_word_unavailable_no_recognizer)
            return
        }
        if (!handoff.canOpenFromBackground()) {
            // Hearing "Hey Curie" in another app could bring up nothing, so nothing listens.
            mutableStatus.value = pausedStatus(R.string.wake_word_paused_notifications_blocked)
            sendStopCommand()
            return
        }

        notifications.cancelResumePrompt()
        if (isServiceRunning) {
            // The running service reports its own state; a repeated start only re-checks the setting.
            return
        }

        mutableStatus.value = pausedStatus(R.string.wake_word_status_starting)
        val listenIntent = WakeWordService.listenIntent(appContext)
        try {
            ContextCompat.startForegroundService(appContext, listenIntent)
            isServiceRequested = true
        } catch (exception: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): not started from an exempt state.
            Log.w(TAG, "Android did not allow listening to start now", exception)
            onStartRefused()
        }
    }

    private fun stopListening() {
        notifications.cancelResumePrompt()
        mutableStatus.value = WakeWordStatus.Disabled
        sendStopCommand()
    }

    /** Stops the listening service, if it runs or was asked to start, without changing the setting. */
    private fun sendStopCommand() {
        if (!isServiceRunning && !isServiceRequested) {
            return
        }

        // A stop command, not stopService(): it queues behind a start the service has not handled
        // yet, and stopping such a service before it called startForeground() crashes the app.
        val stopIntent = WakeWordService.stopIntent(appContext)
        try {
            appContext.startService(stopIntent)
        } catch (exception: IllegalStateException) {
            // Curie is in the background: a pending start reads the (now off) setting and stops itself.
            Log.w(TAG, "The stop command could not be sent", exception)
        }
    }

    /** The status before anything has started: off, missing a permission, or waiting for the app. */
    private suspend fun refreshStatus() {
        val statusBefore = mutableStatus.value
        val enabled = preferences.enabled.first()
        val isStarting = isServiceRunning || isServiceRequested
        val refreshed = when {
            !enabled -> WakeWordStatus.Disabled
            !hasMicrophonePermission() -> WakeWordStatus.MissingPermission
            !isRecognitionAvailable() -> unavailableStatus(R.string.wake_word_unavailable_no_recognizer)
            isStarting -> statusBefore
            else -> waitingStatus()
        }

        // A start or the service may have reported a newer state while the setting was read.
        mutableStatus.compareAndSet(statusBefore, refreshed)
    }

    private fun showResumeNeeded() {
        notifications.showResumePrompt()
        mutableStatus.value = waitingStatus()
    }

    /** On but not listening: opening Curie resumes it, unless Curie could not bring itself up anyway. */
    private fun waitingStatus(): WakeWordStatus {
        if (!handoff.canOpenFromBackground()) {
            return pausedStatus(R.string.wake_word_paused_notifications_blocked)
        }
        return pausedStatus(R.string.wake_word_paused_open_app)
    }

    private fun isRecognitionAvailable(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return true
        }
        val hasOnDeviceApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return hasOnDeviceApi && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    }

    /**
     * The app name of the default speech recognition service, for the disclosure. Settings.Secure
     * "voice_recognition_service" is hidden but readable (SpeechRecognizer resolves the default from
     * it); any failure falls back to a generic name.
     */
    private fun speechServiceLabel(): String? {
        return try {
            val flattened = Settings.Secure.getString(appContext.contentResolver, VOICE_RECOGNITION_SERVICE)
            if (flattened.isNullOrEmpty()) {
                return null
            }
            val component = ComponentName.unflattenFromString(flattened) ?: return null
            applicationLabel(component.packageName)
        } catch (exception: SecurityException) {
            Log.d(TAG, "The default speech service is not readable", exception)
            null
        }
    }

    private fun applicationLabel(packageName: String): String? {
        val packageManager = appContext.packageManager
        return try {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val noFlags = PackageManager.ApplicationInfoFlags.of(0L)
                packageManager.getApplicationInfo(packageName, noFlags)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            val label = packageManager.getApplicationLabel(applicationInfo)
            label.toString()
        } catch (exception: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun pausedStatus(@StringRes reasonText: Int): WakeWordStatus {
        val reason = appContext.getString(reasonText)
        return WakeWordStatus.Paused(reason)
    }

    private fun unavailableStatus(@StringRes reasonText: Int): WakeWordStatus {
        val reason = appContext.getString(reasonText)
        return WakeWordStatus.Unavailable(reason)
    }

    private companion object {
        const val TAG = "WakeWordController"
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
