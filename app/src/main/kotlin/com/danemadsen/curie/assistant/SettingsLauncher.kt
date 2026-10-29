package com.danemadsen.curie.assistant

import android.content.Context
import android.content.Intent

/**
 * Opens Curie's settings screen: the app's launcher activity with [EXTRA_OPEN_SETTINGS] set to true.
 * The activity is found through the package's launcher entry, so this does not depend on its class.
 */
object SettingsLauncher {
    /** Boolean extra: show the settings screen (delivered to onCreate or onNewIntent). */
    const val EXTRA_OPEN_SETTINGS = "com.danemadsen.curie.extra.OPEN_SETTINGS"

    /** Null only if the package has no launcher activity. */
    fun intent(context: Context): Intent? {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launchIntent.putExtra(EXTRA_OPEN_SETTINGS, true)

        // Reuse a running main screen (onNewIntent) instead of stacking a second one on top of it.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return launchIntent
    }
}
