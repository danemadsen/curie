package com.danemadsen.curie.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.danemadsen.curie.AppContainer
import com.danemadsen.curie.CurieApplication
import com.danemadsen.curie.R
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.ui.call.CallScreen
import com.danemadsen.curie.ui.call.CallViewModel
import com.danemadsen.curie.ui.navigation.Destination
import com.danemadsen.curie.ui.navigation.MainViewModel
import com.danemadsen.curie.ui.navigation.SetupRoute
import com.danemadsen.curie.ui.settings.SettingsViewModel
import com.danemadsen.curie.ui.theme.CurieTheme
import kotlinx.coroutines.delay

/**
 * The one way into a call: the chat's phone button, "Hey Curie", the assist gesture and the call
 * notification all open it (see [CallLauncher]). It shows over the lock screen and turns the
 * screen on; anything personal (the assistant's tools) waits for the user to unlock.
 *
 * The intent's extras are informational and never trusted: nothing in them is acted on. Without
 * the model on the device it shows the download instead; with it, it asks for the microphone,
 * starts the call and the [CallService] that keeps it going when Curie leaves the screen.
 */
class CallActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as CurieApplication).container

    private val mainViewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer { MainViewModel(container.settingsRepository.settings, container::hasCompleteDownload) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        // The volume keys change the assistant's voice, not the ringer
        volumeControlStream = AudioManager.STREAM_MUSIC

        val trigger = intent.getStringExtra(CallLauncher.EXTRA_TRIGGER)
        Log.i(TAG, "call requested by ${trigger ?: "an unknown source"}")

        setContent {
            CallActivityContent(
                container = container,
                mainViewModel = mainViewModel,
                onCallStarted = ::onCallStarted,
                onFinished = ::finish,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.callManager.environment.attach(this)
    }

    override fun onStop() {
        container.callManager.environment.attach(null)
        super.onStop()
    }

    /** The call runs: keep it going in a foreground service, started now while the screen is visible. */
    private fun onCallStarted() {
        CallService.start(this)
    }

    private companion object {
        const val TAG = "CallActivity"
    }
}

/**
 * The call, or the model download when the model is not on the device yet (the main screens'
 * start destination says which: the chat needs the same files as a call).
 */
@Composable
private fun CallActivityContent(
    container: AppContainer,
    mainViewModel: MainViewModel,
    onCallStarted: () -> Unit,
    onFinished: () -> Unit,
) {
    val appearance by mainViewModel.appearance.collectAsStateWithLifecycle()
    val startDestination by mainViewModel.startDestination.collectAsStateWithLifecycle()
    val settings = appearance ?: Settings()
    val themeMode = SettingsViewModel.themeModeOf(settings.themeMode)
    val accentColor = Color(settings.accentColor)

    CurieTheme(themeMode = themeMode, accentColor = accentColor, useSystemColors = settings.useSystemColors) {
        when (startDestination) {
            null -> Unit
            Destination.Chat -> CallContent(container, onCallStarted, onFinished)
            else -> SetupContent(container, onFinished)
        }
    }
}

@Composable
private fun SetupContent(container: AppContainer, onFinished: () -> Unit) {
    val setupViewModel = viewModel { container.setupViewModel() }
    // Once the model is downloaded the user starts the call again: the download takes long
    SetupRoute(viewModel = setupViewModel, onModelReady = onFinished)
}

@Composable
private fun CallContent(container: AppContainer, onCallStarted: () -> Unit, onFinished: () -> Unit) {
    val callViewModel = viewModel { container.callViewModel() }
    val state by callViewModel.uiState.collectAsStateWithLifecycle()
    val isCallOver by callViewModel.isCallOver.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current

    val microphoneRequest = ActivityResultContracts.RequestPermission()
    val requestMicrophone = rememberLauncherForActivityResult(microphoneRequest) { isGranted ->
        if (isGranted) {
            callViewModel.startCall(onCallStarted)
        } else {
            val text = resources.getString(R.string.call_microphone_needed)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            callViewModel.startCall(onCallStarted)
        } else {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(isCallOver) {
        if (isCallOver) {
            val problem = when (callViewModel.endReason()) {
                CallEndReason.MODEL_UNAVAILABLE -> R.string.error_model_unavailable
                CallEndReason.MICROPHONE_UNAVAILABLE -> R.string.recording_failed
                CallEndReason.HUNG_UP, CallEndReason.APP_LAUNCHED, null -> null
            }
            if (problem != null) {
                val text = resources.getString(problem)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }

            // "Call ended" stays a moment, like a phone's
            delay(ENDED_DISPLAY_MS)
            onFinished()
        }
    }

    CallScreen(state = state, onAction = callViewModel::onAction, audioLevel = callViewModel::audioLevel)
}

private const val ENDED_DISPLAY_MS = 800L
