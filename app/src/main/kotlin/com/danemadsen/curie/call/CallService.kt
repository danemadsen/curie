package com.danemadsen.curie.call

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.danemadsen.curie.CurieApplication
import com.danemadsen.curie.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a call going when Curie leaves the screen: a foreground service of the microphone and
 * media playback types with the ongoing call notification. The call itself lives in the
 * [CallManager]; this service only keeps the process allowed to record and play, and stops when
 * the call ends.
 *
 * It must be started while Curie is visible (the call screen starts it): Android refuses a
 * microphone foreground service started from the background.
 */
class CallService : LifecycleService() {
    private val callManager: CallManager
        get() = (application as CurieApplication).container.callManager

    private var isForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_HANG_UP) {
            callManager.hangUp()
            if (!isForeground) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // Started with startForegroundService: go to the foreground first, even to stop at once
        val isStarted = enterForeground()
        val call = callManager.call.value
        if (!isStarted || call == null || !call.isRunning) {
            stopService()
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            showAssistantName()
            call.job.join()
            stopService()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (isForeground) {
            return true
        }
        val notifications = CallNotifications(this)
        notifications.createChannel()
        val notification = notifications.build(Settings.DEFAULT_ASSISTANT_NAME)

        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        try {
            ServiceCompat.startForeground(this, CallNotifications.NOTIFICATION_ID, notification, types)
            isForeground = true
        } catch (error: SecurityException) {
            // No microphone permission, or not started while visible: the call runs only on screen
            Log.w(TAG, "the call cannot run in the background", error)
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "the call cannot run in the background", error)
            } else {
                throw error
            }
        }
        return isForeground
    }

    /** Puts the assistant's chosen name in the notification once the settings are read. */
    private suspend fun showAssistantName() {
        val container = (application as CurieApplication).container
        val settings = container.settingsRepository.settings.first()
        if (settings.assistantName == Settings.DEFAULT_ASSISTANT_NAME) {
            return
        }
        val notification = CallNotifications(this).build(settings.assistantName)
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(CallNotifications.NOTIFICATION_ID, notification)
            } catch (error: SecurityException) {
                Log.d(TAG, "the call notification cannot be updated", error)
            }
        }
    }

    private fun stopService() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_HANG_UP = "com.danemadsen.curie.action.HANG_UP"

        private const val TAG = "CallService"

        /** Starts the service from the visible call screen; false when Android refuses. */
        fun start(context: Context): Boolean {
            val intent = Intent(context, CallService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the app was not visible after all
                Log.w(TAG, "the call service could not start", error)
                false
            } catch (error: SecurityException) {
                Log.w(TAG, "the call service could not start", error)
                false
            }
        }
    }
}
