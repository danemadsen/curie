package com.danemadsen.curie.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.theme.ThemeMode

/** Everything the settings screen shows. */
@Immutable
data class SettingsUiState(
    val user: Persona,
    val assistant: Persona,
    val voiceAssistant: VoiceAssistantUiState,

    /** When Curie reads its chat replies aloud. */
    val readAloud: ReadAloud,

    val themeMode: ThemeMode,

    /** The seed of the colour scheme; the hue slider keeps its saturation and brightness. */
    val accentColor: Color,

    /** The colours come from the system's palette instead of [accentColor]. */
    val useSystemColors: Boolean,

    /** The phone has a system palette (Android 12 and later), so [useSystemColors] can apply. */
    val areSystemColorsAvailable: Boolean,

    val modelStatus: ModelStatus,

    /** The size of the model download, in bytes. */
    val modelSizeBytes: Long,

    /** The version shown under About, such as "1.0.0 (412)". */
    val appVersion: String,
)

/** The voice assistant settings and the permissions they depend on. */
@Immutable
data class VoiceAssistantUiState(
    /** Listening for "Hey Curie" in the background. */
    val isWakeWordEnabled: Boolean,

    /** Curie holds the device's assistant role. */
    val isDefaultAssistant: Boolean,

    val isMicrophoneGranted: Boolean,

    /** Always true where notifications need no permission (before Android 13). */
    val isNotificationGranted: Boolean,
)

/** When Curie reads its chat replies aloud (a call always speaks). */
enum class ReadAloud {
    NEVER,

    /** Only replies to voice messages. */
    VOICE_MESSAGES,
    ALWAYS,
}

/** A runtime permission the settings may ask for. */
enum class AppPermission {
    MICROPHONE,
    NOTIFICATIONS,
}

/** What the user asks for on the settings screens. */
sealed interface SettingsAction {
    data object Back : SettingsAction

    data object PickUserImage : SettingsAction

    data class SetUserName(val name: String) : SettingsAction

    data object PickAssistantImage : SettingsAction

    data class SetAssistantName(val name: String) : SettingsAction

    data class SetWakeWordEnabled(val enabled: Boolean) : SettingsAction

    data class SetReadAloud(val readAloud: ReadAloud) : SettingsAction

    /** Ask the system to make Curie the default assistant. */
    data object BecomeDefaultAssistant : SettingsAction

    /** Ask for [permission], or open the app's system settings if it can no longer be asked for. */
    data class RequestPermission(val permission: AppPermission) : SettingsAction

    data class SetThemeMode(val mode: ThemeMode) : SettingsAction

    data class SetAccentColor(val color: Color) : SettingsAction

    data class SetUseSystemColors(val enabled: Boolean) : SettingsAction

    data object DownloadModel : SettingsAction

    data object CancelDownload : SettingsAction

    /** Delete the model's files (already confirmed by the user). */
    data object DeleteModel : SettingsAction

    /** Delete the model's files and download them again (already confirmed by the user). */
    data object RedownloadModel : SettingsAction

    data object OpenLicenses : SettingsAction
}
