package com.danemadsen.curie.assistant

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import com.danemadsen.curie.R

/**
 * Curie as the default digital assistant. The ASSISTANT role cannot be requested in-app (the role is
 * not requestable: RoleManager's request returns RESULT_CANCELED without any UI), so onboarding opens
 * the Settings screen where the user picks the assistant. Check the state again in onResume.
 */
object AssistantRole {
    /** PackageManager.FEATURE_VOICE_RECOGNIZERS, which is not in the public SDK. */
    private const val FEATURE_VOICE_RECOGNIZERS = "android.software.voice_recognizers"

    /** Settings screens for picking the assistant, most specific first. */
    private val SETTINGS_ACTIONS = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )

    /** Whether the user picked Curie as the default digital assistant. */
    fun isDefaultAssistant(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            return false
        }
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /**
     * Whether the system has bound Curie's voice interaction service. Only then may Curie start
     * activities and a microphone foreground service from the background; holding the role is not
     * enough (Android 10/11, low-RAM devices and a pending reselection keep the service unbound).
     */
    fun isActiveVoiceInteractionService(context: Context): Boolean {
        val serviceComponent = ComponentName(context, CurieVoiceInteractionService::class.java)
        return VoiceInteractionService.isActiveService(context, serviceComponent)
    }

    /**
     * Curie holds the role but its voice interaction service is not active, which happens when an app
     * update added the service: restarting the phone, or picking another assistant and then Curie
     * again, activates it. False where the service cannot run at all.
     */
    fun needsReselection(context: Context): Boolean {
        if (!canRunVoiceInteractionService(context)) {
            return false
        }
        return isDefaultAssistant(context) && !isActiveVoiceInteractionService(context)
    }

    /** What to tell the user when [needsReselection], else null. */
    fun reselectionHint(context: Context): String? {
        if (!needsReselection(context)) {
            return null
        }
        return context.getString(R.string.assistant_role_reselect)
    }

    /** The service is enabled from Android 12, and the system never binds one on low-RAM devices. */
    fun canRunVoiceInteractionService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        if (activityManager.isLowRamDevice) {
            return false
        }
        return context.packageManager.hasSystemFeature(FEATURE_VOICE_RECOGNIZERS)
    }

    /**
     * Opens Settings where the user picks the default digital assistant: the "Digital assistant app"
     * screen, else "Default apps", else Settings itself.
     */
    fun requestIntent(context: Context): Intent {
        val packageManager = context.packageManager
        for (action in SETTINGS_ACTIONS) {
            val settingsIntent = Intent(action)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolves(packageManager, settingsIntent)) {
                return settingsIntent
            }
        }

        val settingsHome = Intent(Settings.ACTION_SETTINGS)
        settingsHome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return settingsHome
    }

    /** Needs the <queries> entries for these Settings actions, or every check fails on API 30+. */
    private fun resolves(packageManager: PackageManager, intent: Intent): Boolean {
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val defaultOnly = PackageManager.MATCH_DEFAULT_ONLY.toLong()
            val flags = PackageManager.ResolveInfoFlags.of(defaultOnly)
            packageManager.resolveActivity(intent, flags)
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolved != null
    }
}
