package com.danemadsen.curie.assistant.wakeword

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * Keeps "Hey Curie" off after the user stopped Curie (Force stop in the app's settings, Stop in the
 * Task Manager from Android 13) until the user starts it again: from the app, with the switch, or
 * with the "Tap to resume" notification.
 *
 * Android binds the active assistant's voice interaction service again right after such a stop
 * (VoiceInteractionManagerService handles both), so without this its onReady() would bring
 * listening straight back against the user's wish.
 *
 * The stops come from the process exit records (Android 11+), whose "user requested" reason also
 * covers a removal from Recents that ended the process. Android writes those records to storage
 * only now and then, so a reboot can drop a recent one: the newest stop seen and the newest stop a
 * start answered are kept in a private preferences file.
 *
 * Before Android 14 an app update leaves the same exit reason. The update broadcast then offers the
 * "Tap to resume" notification (WakeWordController.onSystemStartup).
 */
internal class UserStopHold(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    /** True when the user stopped Curie and listening has not been started on request since. */
    fun isHolding(): Boolean {
        val newestStop = rememberNewestStop()
        return newestStop > storedMillis(KEY_ANSWERED_STOP_MILLIS)
    }

    /** Listening was started on request: the stops before now no longer hold. */
    fun lift() {
        val newestStop = rememberNewestStop()
        if (newestStop <= storedMillis(KEY_ANSWERED_STOP_MILLIS)) {
            return
        }

        preferences.edit { putLong(KEY_ANSWERED_STOP_MILLIS, newestStop) }
    }

    /** The newest user stop, recorded or remembered (wall-clock milliseconds); stores one not seen before. */
    private fun rememberNewestStop(): Long {
        val rememberedStop = storedMillis(KEY_SEEN_STOP_MILLIS)
        val recordedStop = latestRecordedStopMillis() ?: return rememberedStop
        if (recordedStop <= rememberedStop) {
            return rememberedStop
        }

        preferences.edit { putLong(KEY_SEEN_STOP_MILLIS, recordedStop) }
        return recordedStop
    }

    private fun storedMillis(key: String): Long {
        return preferences.getLong(key, NO_STOP_MILLIS)
    }

    /** When Android's exit records last show a user stop, or null when they show none. */
    private fun latestRecordedStopMillis(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        // Every recorded exit of this app's processes (pid 0, no limit), newest first. A stop stays the
        // newest user stop when the process that followed it later died for another reason.
        val exits = activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)
        val userStop = exits.firstOrNull { exit -> exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
        return userStop?.timestamp
    }

    private companion object {
        const val PREFERENCES_FILE = "wake_word_user_stop"
        const val KEY_SEEN_STOP_MILLIS = "seen_stop_millis"
        const val KEY_ANSWERED_STOP_MILLIS = "answered_stop_millis"
        const val NO_STOP_MILLIS = 0L
    }
}
