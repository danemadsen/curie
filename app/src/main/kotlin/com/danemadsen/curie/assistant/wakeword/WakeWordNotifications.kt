package com.danemadsen.curie.assistant.wakeword

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.CallTriggers
import com.danemadsen.curie.assistant.SettingsLauncher
import com.danemadsen.curie.assistant.WakeWordStatus
import com.danemadsen.curie.call.CallLauncher

/**
 * The wake word's notifications: the ongoing "Listening" notice of the foreground service (low
 * importance, with a Turn off action), the "Tap to resume" prompt for when Android would not let
 * listening start in the background, and the heads-up "tap to talk" for when Curie heard its name but
 * may not open the call screen by itself.
 */
internal class WakeWordNotifications(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val listening = NotificationChannelCompat.Builder(CHANNEL_LISTENING, NotificationManagerCompat.IMPORTANCE_LOW)
        listening.setName(context.getString(R.string.wake_word_channel_listening))
        listening.setDescription(context.getString(R.string.wake_word_channel_listening_description))
        listening.setShowBadge(false)

        val resume = NotificationChannelCompat.Builder(CHANNEL_RESUME, NotificationManagerCompat.IMPORTANCE_DEFAULT)
        resume.setName(context.getString(R.string.wake_word_channel_resume))
        resume.setDescription(context.getString(R.string.wake_word_channel_resume_description))

        val heard = NotificationChannelCompat.Builder(CHANNEL_HEARD, NotificationManagerCompat.IMPORTANCE_HIGH)
        heard.setName(context.getString(R.string.wake_word_channel_heard))
        heard.setDescription(context.getString(R.string.wake_word_channel_heard_description))

        val channels = listOf(listening.build(), resume.build(), heard.build())
        notificationManager.createNotificationChannelsCompat(channels)
    }

    /** The foreground service's notification, describing [status]. */
    fun listening(status: WakeWordStatus): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_LISTENING)
        builder.setSmallIcon(R.drawable.ic_wake_word_notification)

        if (status == WakeWordStatus.Listening) {
            builder.setContentTitle(context.getString(R.string.wake_word_notification_listening_title))
            builder.setContentText(context.getString(R.string.wake_word_notification_listening_text))
        } else {
            // Starting or paused: the text says which.
            builder.setContentTitle(context.getString(R.string.wake_word_notification_status_title))
            builder.setContentText(statusText(context, status))
        }

        builder.setOngoing(true)
        builder.setSilent(true)
        builder.setShowWhen(false)
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setPriority(NotificationCompat.PRIORITY_LOW)
        builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val openSettings = openSettingsIntent()
        if (openSettings != null) {
            builder.setContentIntent(openSettings)
        }

        val turnOff = turnOffIntent()
        val turnOffLabel = context.getString(R.string.wake_word_notification_turn_off)
        builder.addAction(R.drawable.ic_wake_word_notification, turnOffLabel, turnOff)
        return builder.build()
    }

    /** Updates the foreground service's notification in place. */
    fun updateListening(status: WakeWordStatus) {
        val notification = listening(status)
        post(LISTENING_ID, notification)
    }

    /** Tapping it starts listening again: a notification tap lets a microphone service start from the background. */
    fun showResumePrompt() {
        val resume = resumeIntent()
        val resumeLabel = context.getString(R.string.wake_word_notification_resume_action)

        val builder = NotificationCompat.Builder(context, CHANNEL_RESUME)
        builder.setSmallIcon(R.drawable.ic_wake_word_notification)
        builder.setContentTitle(context.getString(R.string.wake_word_notification_resume_title))
        builder.setContentText(context.getString(R.string.wake_word_notification_resume_text))
        builder.setContentIntent(resume)
        builder.addAction(R.drawable.ic_wake_word_notification, resumeLabel, resume)
        builder.setAutoCancel(true)
        builder.setOnlyAlertOnce(true)
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val notification = builder.build()
        post(RESUME_ID, notification)
    }

    fun cancelResumePrompt() {
        notificationManager.cancel(RESUME_ID)
    }

    /** Whether [showTapToTalk] would reach the user: notifications allowed and its channel not blocked. */
    fun canShowTapToTalk(): Boolean {
        if (!canPost()) {
            return false
        }
        val channel = notificationManager.getNotificationChannelCompat(CHANNEL_HEARD) ?: return false
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Heads-up "Curie is listening - tap to talk"; tapping opens the call screen. False when not shown. */
    fun showTapToTalk(): Boolean {
        if (!canShowTapToTalk()) {
            return false
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_HEARD)
        builder.setSmallIcon(R.drawable.ic_wake_word_notification)
        builder.setContentTitle(context.getString(R.string.wake_word_notification_heard_title))
        builder.setContentText(context.getString(R.string.wake_word_notification_heard_text))
        builder.setContentIntent(talkIntent())
        builder.setAutoCancel(true)
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        builder.setTimeoutAfter(TAP_TO_TALK_TIMEOUT_MILLIS)

        val notification = builder.build()
        return post(TAP_TO_TALK_ID, notification)
    }

    /** POST_NOTIFICATIONS is a runtime permission from API 33; the user can also block the app's notifications. */
    private fun canPost(): Boolean {
        if (!notificationManager.areNotificationsEnabled()) {
            return false
        }
        val hasRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (!hasRuntimePermission) {
            return true
        }
        val state = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        return state == PackageManager.PERMISSION_GRANTED
    }

    /** False when the notification could not be posted. */
    private fun post(id: Int, notification: Notification): Boolean {
        if (!canPost()) {
            return false
        }

        return try {
            notificationManager.notify(id, notification)
            true
        } catch (exception: SecurityException) {
            // POST_NOTIFICATIONS was revoked a moment ago.
            false
        }
    }

    private fun openSettingsIntent(): PendingIntent? {
        val settingsIntent = SettingsLauncher.intent(context) ?: return null
        return PendingIntent.getActivity(context, REQUEST_OPEN_SETTINGS, settingsIntent, UPDATE_FLAGS)
    }

    private fun turnOffIntent(): PendingIntent {
        val turnOff = WakeWordService.turnOffIntent(context)
        return PendingIntent.getService(context, REQUEST_TURN_OFF, turnOff, UPDATE_FLAGS)
    }

    private fun resumeIntent(): PendingIntent {
        val listen = WakeWordService.listenIntent(context)
        return PendingIntent.getForegroundService(context, REQUEST_RESUME, listen, UPDATE_FLAGS)
    }

    /** Opens the call activity directly: since Android 12 a notification may not use a trampoline. */
    private fun talkIntent(): PendingIntent {
        val callIntent = CallLauncher.intent(context, CallTriggers.NOTIFICATION)
        val options = creatorOptions()
        return PendingIntent.getActivity(context, REQUEST_TALK, callIntent, UPDATE_FLAGS, options)
    }

    /**
     * From Android 15 a PendingIntent's creator only lends its background-start privileges when it
     * opts in (API 34+). The tap itself already allows the start; this keeps the creator's side explicit.
     */
    private fun creatorOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val options = ActivityOptions.makeBasic()
        options.setPendingIntentCreatorBackgroundActivityStartMode(mode)
        return options.toBundle()
    }

    companion object {
        const val LISTENING_ID = 4101
        const val RESUME_ID = 4102
        const val TAP_TO_TALK_ID = 4103

        const val CHANNEL_LISTENING = "wake_word_listening"
        const val CHANNEL_RESUME = "wake_word_resume"
        const val CHANNEL_HEARD = "wake_word_heard"

        private const val REQUEST_OPEN_SETTINGS = 1
        private const val REQUEST_TURN_OFF = 2
        private const val REQUEST_RESUME = 3
        private const val REQUEST_TALK = 4

        private const val UPDATE_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** "Tap to talk" is only meaningful right after the wake word. */
        private const val TAP_TO_TALK_TIMEOUT_MILLIS = 30_000L
    }
}

/** The status as one line of user-facing text (the reason for paused and unavailable states). */
internal fun statusText(context: Context, status: WakeWordStatus): String {
    return when (status) {
        WakeWordStatus.Disabled -> context.getString(R.string.wake_word_status_disabled)
        WakeWordStatus.MissingPermission -> context.getString(R.string.wake_word_status_missing_permission)
        WakeWordStatus.Listening -> context.getString(R.string.wake_word_status_listening)
        is WakeWordStatus.Paused -> status.reason
        is WakeWordStatus.Unavailable -> status.reason
    }
}
