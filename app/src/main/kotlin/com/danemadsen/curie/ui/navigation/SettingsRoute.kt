package com.danemadsen.curie.ui.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danemadsen.curie.assistant.AssistantRole
import com.danemadsen.curie.ui.components.ConfirmDialog
import com.danemadsen.curie.ui.settings.AppPermission
import com.danemadsen.curie.ui.settings.LicensesScreen
import com.danemadsen.curie.ui.settings.SettingsAction
import com.danemadsen.curie.ui.settings.SettingsScreen
import com.danemadsen.curie.ui.settings.SettingsViewModel
import com.danemadsen.curie.ui.settings.readBundledLicenses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The settings with what they need from the activity: the photo picker for the pictures, the
 * permission prompts (after the "Hey Curie" disclosure for the wake word, or the app's system
 * settings once a permission can no longer be asked for) and the system's assistant settings.
 */
@Composable
internal fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var isDisclosureShown by rememberSaveable { mutableStateOf(false) }
    var requestedPermission by rememberSaveable { mutableStateOf<String?>(null) }

    ToastNotices(viewModel.notices)

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val pickUserImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.setUserImage(uri)
        }
    }
    val pickAssistantImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.setAssistantImage(uri)
        }
    }

    // The wake word turns on even if a permission was refused: its status then says what is missing
    val requestWakeWordPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        viewModel.setWakeWordEnabled(true)
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        val permission = requestedPermission
        requestedPermission = null
        val activity = context.findActivity()
        val canAskAgain = permission != null && activity?.shouldShowRequestPermissionRationale(permission) == true
        if (!isGranted && !canAskAgain) {
            // Refused for good: only the system settings can allow it now
            context.openAppSettings()
        }
        viewModel.refresh()
    }

    val onAction: (SettingsAction) -> Unit = { action ->
        when (action) {
            SettingsAction.Back -> onBack()
            SettingsAction.OpenLicenses -> onOpenLicenses()
            SettingsAction.PickUserImage -> {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                pickUserImage.launch(request)
            }
            SettingsAction.PickAssistantImage -> {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                pickAssistantImage.launch(request)
            }
            is SettingsAction.SetWakeWordEnabled -> {
                if (action.enabled) {
                    isDisclosureShown = true
                } else {
                    viewModel.setWakeWordEnabled(false)
                }
            }
            SettingsAction.BecomeDefaultAssistant -> {
                val intent = AssistantRole.requestIntent(context)
                context.startActivitySafely(intent)
            }
            is SettingsAction.RequestPermission -> {
                val permission = manifestPermissionOf(action.permission)
                if (permission != null) {
                    requestedPermission = permission
                    requestPermission.launch(permission)
                }
            }
            else -> viewModel.onAction(action)
        }
    }

    SettingsScreen(state = state, onAction = onAction)

    if (isDisclosureShown) {
        val disclosure = viewModel.wakeWordDisclosure
        ConfirmDialog(
            title = disclosure.title,
            message = disclosure.text,
            confirmLabel = disclosure.acceptLabel,
            dismissLabel = disclosure.declineLabel,
            onConfirm = {
                isDisclosureShown = false
                val permissions = viewModel.wakeWordPermissions.toTypedArray()
                requestWakeWordPermissions.launch(permissions)
            },
            onDismiss = { isDisclosureShown = false },
        )
    }
}

/** The open-source licences, read from the bundled assets off the main thread. */
@Composable
internal fun LicensesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) { readBundledLicenses(context.assets) }
    }
    LicensesScreen(licenses = licenses, onBack = onBack)
}

private fun manifestPermissionOf(permission: AppPermission): String? = when (permission) {
    AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    AppPermission.NOTIFICATIONS -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    }
}
