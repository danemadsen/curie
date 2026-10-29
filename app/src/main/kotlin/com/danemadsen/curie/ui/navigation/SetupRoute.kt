package com.danemadsen.curie.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danemadsen.curie.ui.setup.SetupAction
import com.danemadsen.curie.ui.setup.SetupScreen
import com.danemadsen.curie.ui.setup.SetupViewModel

/**
 * The model download. Starting it asks for the notification permission first (Android 13 and
 * later), so the download's progress notification can show; the download starts either way.
 */
@Composable
internal fun SetupRoute(viewModel: SetupViewModel, onModelReady: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isModelReady by viewModel.isModelReady.collectAsStateWithLifecycle()

    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        viewModel.onAction(SetupAction.Download)
    }

    LaunchedEffect(isModelReady) {
        if (isModelReady) {
            onModelReady()
        }
    }

    val onAction: (SetupAction) -> Unit = { action ->
        val missingPermission = missingNotificationPermission(context)
        if (action == SetupAction.Download && missingPermission != null) {
            requestNotifications.launch(missingPermission)
        } else {
            viewModel.onAction(action)
        }
    }

    SetupScreen(state = state, onAction = onAction)
}

/**
 * The permission Android 13 and later ask for before an app may post notifications; null when it
 * is granted already or the phone does not ask.
 */
private fun missingNotificationPermission(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return null
    }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val state = ContextCompat.checkSelfPermission(context, permission)
    return if (state == PackageManager.PERMISSION_GRANTED) null else permission
}
