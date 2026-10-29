package com.danemadsen.curie.assistant.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danemadsen.curie.assistant.WakeWordControllerOwner

/**
 * After a reboot or an app update, "Hey Curie" is not listening. A microphone foreground service may
 * not start from here (Android 14+ throws, older versions deny it the microphone), so this only offers
 * a "Tap to resume" notification, and not even that when the voice interaction service will start
 * listening by itself.
 */
class WakeWordBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isStartup = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isStartup) {
            return
        }

        val controller = WakeWordControllerOwner.controller(context) ?: return
        val pendingResult = goAsync()
        val startupJob = controller.onSystemStartup()
        startupJob.invokeOnCompletion { pendingResult.finish() }
    }
}
