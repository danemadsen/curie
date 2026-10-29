package com.danemadsen.curie.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.danemadsen.curie.R

/** The ongoing call notification: who is on the call, a tap back to it, and "End call". */
internal class CallNotifications(private val context: Context) {
    fun createChannel() {
        val builder = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        builder.setName(context.getString(R.string.call_channel_name))
        builder.setDescription(context.getString(R.string.call_channel_description))
        builder.setShowBadge(false)

        val channel = builder.build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(assistantName: String): Notification {
        val title = context.getString(R.string.call_notification_title, assistantName)
        val endLabel = context.getString(R.string.call_end)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        builder.setSmallIcon(R.drawable.tabler_phone_call)
        builder.setContentTitle(title)
        builder.setContentText(context.getString(R.string.call_notification_text))
        builder.setOngoing(true)
        builder.setSilent(true)
        builder.setCategory(NotificationCompat.CATEGORY_CALL)
        builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder.setUsesChronometer(true)
        builder.setContentIntent(openCallIntent())
        builder.addAction(R.drawable.tabler_phone_off, endLabel, hangUpIntent())
        return builder.build()
    }

    /** Back to the call screen, which shows the running call. */
    private fun openCallIntent(): PendingIntent {
        val intent = Intent(context, CallActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, flags)
    }

    private fun hangUpIntent(): PendingIntent {
        val intent = Intent(context, CallService::class.java)
        intent.action = CallService.ACTION_HANG_UP
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(context, REQUEST_HANG_UP, intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "call"
        const val NOTIFICATION_ID = 2001

        private const val REQUEST_OPEN = 1
        private const val REQUEST_HANG_UP = 2
    }
}
