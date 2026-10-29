package com.danemadsen.curie.model

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The model download's notification channel and its foreground notification.
 *
 * The texts are English literals: move them to string resources when the app is localized.
 */
internal class ModelDownloadNotifications(private val context: Context) {
    fun createChannel() {
        val builder = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        builder.setName(CHANNEL_NAME)
        builder.setDescription(CHANNEL_DESCRIPTION)
        builder.setShowBadge(false)

        val channel = builder.build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** The notification for [progress] (null: starting), with a cancel action. */
    fun build(progress: DownloadProgress?, cancelIntent: PendingIntent): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setSilent(true)
        builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder.setContentIntent(openAppIntent())
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, CANCEL_ACTION, cancelIntent)

        if (progress == null || progress.phase == DownloadPhase.VERIFYING) {
            val title = if (progress == null) STARTING_TITLE else VERIFYING_TITLE
            builder.setContentTitle(title)
            builder.setProgress(0, 0, true)
            return builder.build()
        }

        val done = Formatter.formatShortFileSize(context, progress.doneBytes)
        val total = Formatter.formatShortFileSize(context, progress.totalBytes)
        val permille = (progress.doneBytes * PROGRESS_MAX / progress.totalBytes.coerceAtLeast(1)).toInt()
        builder.setContentTitle(DOWNLOADING_TITLE)
        builder.setContentText("$done of $total")
        builder.setProgress(PROGRESS_MAX, permille, false)
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 1001

        private const val CHANNEL_NAME = "Model download"
        private const val CHANNEL_DESCRIPTION = "Progress of the download of Curie's language model"
        private const val STARTING_TITLE = "Preparing the Curie model download"
        private const val VERIFYING_TITLE = "Verifying the Curie model"
        private const val DOWNLOADING_TITLE = "Downloading the Curie model"
        private const val CANCEL_ACTION = "Cancel"
        private const val PROGRESS_MAX = 1000
    }
}
