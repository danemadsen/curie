package com.danemadsen.curie.assistant.wakeword

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.AssistantRole
import com.danemadsen.curie.assistant.VisibleOrAssistantPolicy
import com.danemadsen.curie.assistant.WakeWordController
import com.danemadsen.curie.assistant.WakeWordControllerOwner
import com.danemadsen.curie.assistant.WakeWordStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The microphone foreground service that listens for "Hey Curie". It is started only from a visible
 * activity, a notification tap or the active voice interaction service ([WakeWordController] makes
 * sure of that), and shows an ongoing low-importance notification with a Turn off action.
 */
class WakeWordService : LifecycleService() {
    private lateinit var notifications: WakeWordNotifications
    private var controller: WakeWordController? = null
    private var engineJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        notifications = WakeWordNotifications(applicationContext)

        val owner = WakeWordControllerOwner.controller(this)
        controller = owner
        owner?.onServiceCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TURN_OFF -> {
                controller?.turnOffFromNotification()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // ACTION_LISTEN, or no intent at all: a sticky restart after Android ended the process.
        val isStickyRestart = intent == null
        return startListening(isStickyRestart)
    }

    override fun onDestroy() {
        controller?.onServiceDestroyed()
        // Cancels the engine, which releases the recognizer and the microphone.
        super.onDestroy()
    }

    private fun startListening(isStickyRestart: Boolean): Int {
        // Every startForegroundService() must be answered with startForeground(), even by a start that
        // stops at once: stopping before it would crash the app.
        val isInForeground = enterForeground()
        val currentController = controller
        if (currentController == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isInForeground) {
            // On API 34+ a missing RECORD_AUDIO also makes the microphone service fail to start.
            if (currentController.hasMicrophonePermission()) {
                currentController.onStartRefused()
            } else {
                currentController.onMissingPermission()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // A sticky restart runs in the background, where only the active assistant gets the microphone.
        val hasBackgroundMicrophone = AssistantRole.isActiveVoiceInteractionService(this)
        if (isStickyRestart && !hasBackgroundMicrophone) {
            currentController.onStartRefused()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!currentController.hasMicrophonePermission()) {
            currentController.onMissingPermission()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isStickyRestart) {
            currentController.onRequestedStart()
        }
        startEngine(currentController)
        return START_STICKY
    }

    /** False when Android refuses the microphone service here (a start from the background). */
    private fun enterForeground(): Boolean {
        if (isForeground) {
            return true
        }

        val starting = getString(R.string.wake_word_status_starting)
        val startingStatus = WakeWordStatus.Paused(starting)
        val notification = notifications.listening(startingStatus)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, WakeWordNotifications.LISTENING_ID, notification, serviceType)
            isForeground = true
            true
        } catch (exception: SecurityException) {
            // API 34+: a microphone service created without while-in-use access.
            Log.w(TAG, "Android refused the microphone service", exception)
            false
        } catch (exception: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "Android refused the foreground service", exception)
            false
        }
    }

    private fun startEngine(controller: WakeWordController) {
        if (engineJob?.isActive == true) {
            return
        }

        engineJob = lifecycleScope.launch {
            // Turned off while this start was on its way (for example from an old notification).
            if (!controller.isEnabled()) {
                controller.onFoundDisabled()
                stopSelf()
                return@launch
            }

            controller.onStarting()
            launch {
                controller.status.collect { status -> showInNotification(status) }
            }

            val engine = createEngine(controller)
            engine.run()
        }
    }

    private fun showInNotification(status: WakeWordStatus) {
        val isRunningState = status == WakeWordStatus.Listening || status is WakeWordStatus.Paused
        if (isRunningState) {
            notifications.updateListening(status)
        }
    }

    /**
     * The recognizer, the recording and the broadcast receivers use the application context: their
     * cleanup runs just after onDestroy(), when this service's own context is already torn down.
     */
    private fun createEngine(controller: WakeWordController): WakeWordEngine {
        val appContext = applicationContext
        val startPolicy = VisibleOrAssistantPolicy(appContext)
        val handoff = WakeWordHandoff(appContext, notifications, startPolicy)
        val engineListener = ServiceEngineListener(controller, handoff)

        val policy = RestartPolicy(SystemClock::elapsedRealtime, Random.Default)
        val recognizers = PlatformRecognizerFactory(appContext)
        val canUseOwnMicrophone = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val recognition = RecognitionLoop(
            recognizers = recognizers,
            createFeed = { AudioRecordFeed(appContext) },
            policy = policy,
            elapsedRealtime = SystemClock::elapsedRealtime,
            canUseOwnMicrophone = canUseOwnMicrophone,
            listener = engineListener,
        )

        val conditions = deviceConditions(appContext)
        return WakeWordEngine(conditions, controller.microphoneArbiter, recognition, engineListener)
    }

    private inner class ServiceEngineListener(
        private val controller: WakeWordController,
        private val handoff: WakeWordHandoff,
    ) : WakeWordEngineListener {
        override fun onListening() {
            controller.onListening()
        }

        override fun onPaused(reason: PauseReason) {
            controller.onPaused(reason)
        }

        override fun onWakeWord(): Boolean {
            val handoffKind = handoff.openConversation()
            Log.i(TAG, "Heard the wake word; opened the conversation via $handoffKind")
            return handoffKind != Handoff.NONE
        }

        override fun onStopped(reason: StopReason) {
            controller.onListeningStopped(reason)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "WakeWordService"

        private const val ACTION_LISTEN = "com.danemadsen.curie.assistant.action.LISTEN"
        private const val ACTION_STOP = "com.danemadsen.curie.assistant.action.STOP_LISTENING"
        private const val ACTION_TURN_OFF = "com.danemadsen.curie.assistant.action.TURN_OFF_WAKE_WORD"

        /** For startForegroundService() from an allowed state only (see [WakeWordController]). */
        internal fun listenIntent(context: Context): Intent {
            val intent = Intent(context, WakeWordService::class.java)
            intent.action = ACTION_LISTEN
            return intent
        }

        /** For startService(): stops listening without changing the setting. */
        internal fun stopIntent(context: Context): Intent {
            val intent = Intent(context, WakeWordService::class.java)
            intent.action = ACTION_STOP
            return intent
        }

        /** The notification's Turn off: switches "Hey Curie" off and stops. */
        internal fun turnOffIntent(context: Context): Intent {
            val intent = Intent(context, WakeWordService::class.java)
            intent.action = ACTION_TURN_OFF
            return intent
        }
    }
}
