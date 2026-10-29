package com.danemadsen.curie.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.danemadsen.curie.assistant.ActivityStartPolicy

/** Whether an action leaves Curie for another app, which waits until Curie has finished answering. */
internal enum class LaunchTiming {
    SWITCHES_APP,

    /** The target does its work without taking over the screen, such as a clock app setting a timer. */
    IN_PLACE,
}

internal sealed interface LaunchResult {
    data class Started(val intent: Intent) : LaunchResult

    /** Nothing started yet: [intent] starts once Curie has finished answering. */
    data class Deferred(val intent: Intent) : LaunchResult

    /** No app on this device handles any of the candidates. */
    data object NoHandler : LaunchResult

    /** Curie is in the background, where Android would silently block the start. */
    data object NotVisible : LaunchResult
}

/** The candidate that started (or will start after the answer), when one did. */
internal val LaunchResult.chosenIntent: Intent?
    get() = when (this) {
        is LaunchResult.Started -> intent
        is LaunchResult.Deferred -> intent
        LaunchResult.NoHandler -> null
        LaunchResult.NotVisible -> null
    }

/**
 * Starts the first candidate intent an app handles. Every external launch is a new task: the
 * background activity start exemptions only apply to launches that create or bring forward a task.
 */
internal class ActivityLauncher(
    private val context: Context,
    private val startPolicy: ActivityStartPolicy,
) {
    fun launch(candidates: List<Intent>, origin: ToolOrigin, timing: LaunchTiming): LaunchResult {
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // A chat's later rounds and its answer need Curie on screen, so even there the switch waits.
        // A call's screen may be behind the lock screen until the user unlocks, so only a chat checks now.
        val isSwitch = timing == LaunchTiming.SWITCHES_APP
        val needsVisibility = !isSwitch || origin == ToolOrigin.CHAT
        if (needsVisibility && !startPolicy.canStartActivities()) {
            return LaunchResult.NotVisible
        }

        if (isSwitch) {
            return deferFirstResolvable(candidates)
        }
        return startFirstAvailable(candidates)
    }

    /** Starts an intent a tool deferred; false when Android would block it or no app handles it any more. */
    fun startDeferred(intent: Intent): Boolean {
        if (!startPolicy.canStartActivities()) {
            return false
        }
        val candidates = listOf(intent)
        val result = startFirstAvailable(candidates)
        return result is LaunchResult.Started
    }

    private fun startFirstAvailable(candidates: List<Intent>): LaunchResult {
        for (candidate in candidates) {
            try {
                context.startActivity(candidate)
                return LaunchResult.Started(candidate)
            } catch (exception: ActivityNotFoundException) {
                Log.d(TAG, "No activity for ${candidate.action}, trying the next candidate")
            } catch (exception: SecurityException) {
                Log.w(TAG, "Not allowed to start ${candidate.action}, trying the next candidate", exception)
            }
        }
        return LaunchResult.NoHandler
    }

    private fun deferFirstResolvable(candidates: List<Intent>): LaunchResult {
        val candidate = firstResolvable(candidates) ?: return LaunchResult.NoHandler
        return LaunchResult.Deferred(candidate)
    }

    /**
     * The first candidate an app on this device handles, as a new task. Resolving needs the matching
     * <queries> entry in the manifest, which every candidate has.
     */
    fun firstResolvable(candidates: List<Intent>): Intent? {
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolves(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun resolves(intent: Intent): Boolean {
        val packageManager = context.packageManager
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

    private companion object {
        const val TAG = "ActivityLauncher"
    }
}
