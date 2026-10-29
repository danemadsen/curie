package com.danemadsen.curie.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danemadsen.curie.BuildConfig
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.AssistantRole
import com.danemadsen.curie.assistant.WakeWordController
import com.danemadsen.curie.assistant.WakeWordDisclosure
import com.danemadsen.curie.data.AttachmentImportException
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.data.SettingsRepository
import com.danemadsen.curie.data.SpeakReplies
import com.danemadsen.curie.data.ThemeMode as StoredThemeMode
import com.danemadsen.curie.engine.ModelFiles
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.setup.ModelDownloads
import com.danemadsen.curie.ui.theme.ThemeMode
import com.danemadsen.curie.ui.theme.areSystemColorsAvailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** The model's files as the settings manage them. */
interface ModelControls : ModelDownloads {
    /** Deletes every model file; false when some could not be deleted. */
    suspend fun deleteModel(): Boolean
}

/**
 * The settings screen's state holder: the user's and the assistant's names and pictures, the
 * voice assistant ("Hey Curie", the default assistant role and their permissions), the theme, and
 * the model's files.
 */
class SettingsViewModel(
    private val context: Application,
    private val settingsRepository: SettingsRepository,
    modelStatus: StateFlow<ModelStatus>,
    private val modelControls: ModelControls,
    private val engine: AssistantEngine,
    private val wakeWord: WakeWordController,
) : ViewModel() {
    private val platform = MutableStateFlow(readPlatformState())
    private val accentWrites = MutableStateFlow<Int?>(null)
    private val noticeChannel = Channel<String>(Channel.BUFFERED)

    /** User-facing messages: a failed save, an unusable image. */
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    private val initialState = toUiState(Settings(), modelStatus.value, platform.value, pendingAccent = null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        modelStatus,
        platform,
        accentWrites,
    ) { settings, status, platformState, pendingAccent ->
        toUiState(settings, status, platformState, pendingAccent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState)

    /** The "Hey Curie" disclosure to show before asking for the microphone. */
    val wakeWordDisclosure: WakeWordDisclosure
        get() = wakeWord.disclosure()

    /** The permissions "Hey Curie" asks for right after the disclosure. */
    val wakeWordPermissions: List<String>
        get() = wakeWord.requiredPermissions

    init {
        // The hue slider moves continuously: store the latest colour, not every step
        viewModelScope.launch {
            accentWrites.filterNotNull().collect { color ->
                save { settings -> settings.copy(accentColor = color) }
            }
        }
    }

    /** Re-reads what can change outside the app: permissions and the assistant role (call on resume). */
    fun refresh() {
        platform.value = readPlatformState()
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetUserName -> {
                val name = action.name.trim().ifEmpty { Settings.DEFAULT_USER_NAME }
                launchSave { settings -> settings.copy(userName = name) }
            }
            is SettingsAction.SetAssistantName -> {
                val name = action.name.trim().ifEmpty { Settings.DEFAULT_ASSISTANT_NAME }
                launchSave { settings -> settings.copy(assistantName = name) }
            }
            is SettingsAction.SetReadAloud -> {
                val speakReplies = speakRepliesOf(action.readAloud)
                launchSave { settings -> settings.copy(speakReplies = speakReplies) }
            }
            is SettingsAction.SetThemeMode -> {
                val mode = storedThemeModeOf(action.mode)
                launchSave { settings -> settings.copy(themeMode = mode) }
            }
            is SettingsAction.SetAccentColor -> accentWrites.value = action.color.toArgb()
            is SettingsAction.SetUseSystemColors -> {
                val enabled = action.enabled
                launchSave { settings -> settings.copy(useSystemColors = enabled) }
            }
            is SettingsAction.SetWakeWordEnabled -> setWakeWordEnabled(action.enabled)
            SettingsAction.DownloadModel -> modelControls.startDownload()
            SettingsAction.CancelDownload -> modelControls.cancelDownload()
            SettingsAction.DeleteModel -> deleteModel(downloadAgain = false)
            SettingsAction.RedownloadModel -> deleteModel(downloadAgain = true)
            SettingsAction.Back,
            SettingsAction.PickUserImage,
            SettingsAction.PickAssistantImage,
            SettingsAction.BecomeDefaultAssistant,
            is SettingsAction.RequestPermission,
            SettingsAction.OpenLicenses,
            -> Unit
        }
    }

    /** Turns "Hey Curie" on or off; switching it on comes after the disclosure and the permission prompts. */
    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                wakeWord.setEnabled(enabled)
            } catch (error: IOException) {
                Log.w(TAG, "saving the wake word switch failed", error)
                noticeChannel.trySend(context.getString(R.string.settings_saving_failed))
            }
            refresh()
        }
    }

    fun setUserImage(uri: Uri) {
        viewModelScope.launch {
            replaceImage { settingsRepository.setUserImage(uri) }
        }
    }

    fun setAssistantImage(uri: Uri) {
        viewModelScope.launch {
            replaceImage { settingsRepository.setAssistantImage(uri) }
        }
    }

    private suspend fun replaceImage(replace: suspend () -> Unit) {
        try {
            replace()
        } catch (error: AttachmentImportException) {
            Log.i(TAG, "the image was refused: ${error.message}")
            noticeChannel.trySend(context.getString(R.string.settings_image_failed))
        } catch (error: IOException) {
            Log.w(TAG, "saving the image failed", error)
            noticeChannel.trySend(context.getString(R.string.settings_saving_failed))
        }
    }

    private fun deleteModel(downloadAgain: Boolean) {
        viewModelScope.launch {
            // The engine maps the model's files: free it before they go
            engine.unload()
            val isDeleted = modelControls.deleteModel()
            if (!isDeleted) {
                noticeChannel.trySend(context.getString(R.string.settings_delete_model_failed))
            }
            if (downloadAgain) {
                modelControls.startDownload()
            }
        }
    }

    private fun launchSave(transform: (Settings) -> Settings) {
        viewModelScope.launch { save(transform) }
    }

    private suspend fun save(transform: (Settings) -> Settings) {
        try {
            settingsRepository.update(transform)
        } catch (error: IOException) {
            Log.w(TAG, "saving the settings failed", error)
            noticeChannel.trySend(context.getString(R.string.settings_saving_failed))
        }
    }

    private fun toUiState(
        settings: Settings,
        status: ModelStatus,
        platformState: PlatformState,
        pendingAccent: Int?,
    ): SettingsUiState {
        val voiceAssistant = VoiceAssistantUiState(
            isWakeWordEnabled = settings.wakeWordEnabled,
            isDefaultAssistant = platformState.isDefaultAssistant,
            isMicrophoneGranted = platformState.isMicrophoneGranted,
            isNotificationGranted = platformState.isNotificationGranted,
        )
        val accent = pendingAccent ?: settings.accentColor
        return SettingsUiState(
            user = Persona(settings.userName, settings.userImage),
            assistant = Persona(settings.assistantName, settings.assistantImage),
            voiceAssistant = voiceAssistant,
            readAloud = readAloudOf(settings.speakReplies),
            themeMode = themeModeOf(settings.themeMode),
            accentColor = Color(accent),
            useSystemColors = settings.useSystemColors,
            areSystemColorsAvailable = areSystemColorsAvailable,
            modelStatus = status,
            modelSizeBytes = ModelFiles.DOWNLOAD_SIZE,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
    }

    private fun readPlatformState(): PlatformState {
        val isMicrophoneGranted = isGranted(Manifest.permission.RECORD_AUDIO)
        val isNotificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val isDefaultAssistant = AssistantRole.isDefaultAssistant(context)
        return PlatformState(isDefaultAssistant, isMicrophoneGranted, isNotificationGranted)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class PlatformState(
        val isDefaultAssistant: Boolean,
        val isMicrophoneGranted: Boolean,
        val isNotificationGranted: Boolean,
    )

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        fun themeModeOf(mode: StoredThemeMode): ThemeMode = when (mode) {
            StoredThemeMode.SYSTEM -> ThemeMode.SYSTEM
            StoredThemeMode.LIGHT -> ThemeMode.LIGHT
            StoredThemeMode.DARK -> ThemeMode.DARK
        }

        fun readAloudOf(speakReplies: SpeakReplies): ReadAloud = when (speakReplies) {
            SpeakReplies.NEVER -> ReadAloud.NEVER
            SpeakReplies.VOICE_MESSAGES -> ReadAloud.VOICE_MESSAGES
            SpeakReplies.ALWAYS -> ReadAloud.ALWAYS
        }

        fun speakRepliesOf(readAloud: ReadAloud): SpeakReplies = when (readAloud) {
            ReadAloud.NEVER -> SpeakReplies.NEVER
            ReadAloud.VOICE_MESSAGES -> SpeakReplies.VOICE_MESSAGES
            ReadAloud.ALWAYS -> SpeakReplies.ALWAYS
        }

        fun storedThemeModeOf(mode: ThemeMode): StoredThemeMode = when (mode) {
            ThemeMode.SYSTEM -> StoredThemeMode.SYSTEM
            ThemeMode.LIGHT -> StoredThemeMode.LIGHT
            ThemeMode.DARK -> StoredThemeMode.DARK
        }
    }
}
