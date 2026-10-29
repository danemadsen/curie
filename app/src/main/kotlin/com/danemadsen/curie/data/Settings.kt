package com.danemadsen.curie.data

import androidx.annotation.ColorInt

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** When answers are spoken aloud. */
enum class SpeakReplies {
    NEVER,

    /** Only answers to voice messages. */
    VOICE_MESSAGES,
    ALWAYS,
}

/** The user's preferences; the defaults are what a fresh install shows. */
data class Settings(
    val userName: String = DEFAULT_USER_NAME,

    /**
     * The user's avatar: an image file in app storage ([SettingsRepository.setUserImage]); null
     * for the default avatar, also when the file is gone.
     */
    val userImage: String? = null,

    val assistantName: String = DEFAULT_ASSISTANT_NAME,

    /** The assistant's avatar, as [userImage] ([SettingsRepository.setAssistantImage]). */
    val assistantImage: String? = null,

    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /** ARGB. */
    @ColorInt
    val accentColor: Int = DEFAULT_ACCENT_COLOR,

    /**
     * Colours the app from the system's palette (Material You, Android 12 and later) instead of
     * [accentColor], so that Curie blends in with the rest of the phone.
     */
    val useSystemColors: Boolean = true,

    val wakeWordEnabled: Boolean = false,
    val speakReplies: SpeakReplies = SpeakReplies.VOICE_MESSAGES,
    val downloadOnWifiOnly: Boolean = true,
) {
    companion object {
        const val DEFAULT_USER_NAME = "User"
        const val DEFAULT_ASSISTANT_NAME = "Curie"

        @ColorInt
        const val DEFAULT_ACCENT_COLOR: Int = 0xFF2196F3.toInt()
    }
}
