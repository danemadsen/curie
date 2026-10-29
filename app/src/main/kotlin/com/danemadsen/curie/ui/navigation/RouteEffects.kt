package com.danemadsen.curie.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

private const val TAG = "RouteEffects"

/** Shows each of [notices] as a toast while the screen is started. */
@Composable
internal fun ToastNotices(notices: Flow<String>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(notices, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            notices.collect { text -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }
}

/** Starts [intent]; false (logged) when no app handles it or it is not allowed. */
internal fun Context.startActivitySafely(intent: Intent): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "nothing handles $intent", error)
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "not allowed to start $intent", error)
        false
    }
}

/** Curie's page in the system settings, where a permanently refused permission can be allowed. */
internal fun Context.openAppSettings() {
    val uri = Uri.fromParts("package", packageName, null)
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivitySafely(intent)
}

/** The activity of a composition's context. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
