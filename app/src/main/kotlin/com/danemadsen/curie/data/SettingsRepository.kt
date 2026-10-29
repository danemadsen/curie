package com.danemadsen.curie.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A damaged settings file is replaced by the defaults. Without it the file would read as the
 * defaults but fail every write, so the user could never change a setting again.
 */
internal fun settingsCorruptionHandler() = ReplaceFileCorruptionHandler { _ -> emptyPreferences() }

/** One DataStore per file and process: the delegate keeps the single instance. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler(),
)

/**
 * The user's preferences (DataStore). Only values that differ from what is stored are written, so
 * a setting the user never changed follows the app's default.
 */
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val profileImages: AttachmentStore,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.settingsDataStore,
        profileImages = AttachmentStore(
            context = context.applicationContext,
            directory = File(context.filesDir, PROFILE_DIRECTORY_NAME),
            limits = PROFILE_IMAGE_LIMITS,
            probe = AndroidMediaProbe(),
        ),
    )

    /** Unreadable storage reads as the defaults rather than failing the UI. */
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error !is IOException) {
            throw error
        }
        emit(emptyPreferences())
    }

    val settings: Flow<Settings> = observeSettings()

    /**
     * Applies [transform] to the current settings atomically; throws IOException when they cannot
     * be written. The avatars change only through [setUserImage] and [setAssistantImage] (or to
     * null): a path outside the app's avatar storage is an IllegalArgumentException.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { stored ->
            val current = toSettings(stored)
            val updated = transform(current)
            writeChanges(stored, current, updated)
        }
    }

    /**
     * Copies the image at [source] (a picked content Uri, whose read grant does not last) into app
     * storage as the user's avatar; null goes back to the default. Throws [AttachmentImportException].
     */
    suspend fun setUserImage(source: Uri?) {
        replaceProfileImage(source, Settings::userImage) { settings, path -> settings.copy(userImage = path) }
    }

    /** As [setUserImage], for the assistant's avatar. */
    suspend fun setAssistantImage(source: Uri?) {
        replaceProfileImage(source, Settings::assistantImage) { settings, path -> settings.copy(assistantImage = path) }
    }

    /** Stores the new image, points the setting at it, then deletes the image it replaced. */
    private suspend fun replaceProfileImage(
        source: Uri?,
        read: (Settings) -> String?,
        assign: (Settings, String?) -> Settings,
    ) {
        val newPath = source?.let { uri -> importProfileImage(uri) }

        var oldPath: String? = null
        try {
            update { settings ->
                oldPath = read(settings)
                assign(settings, newPath)
            }
        } catch (error: Throwable) {
            if (newPath != null) {
                deleteProfileImage(newPath)
            }
            throw error
        }

        val replacedPath = oldPath
        if (replacedPath != null && replacedPath != newPath) {
            deleteProfileImage(replacedPath)
        }
    }

    private suspend fun importProfileImage(source: Uri): String {
        val image = profileImages.import(source, PROFILE_IMAGE_TYPE_HINT)
        if (image.kind != AttachmentKind.IMAGE) {
            deleteProfileImage(image.path)
            throw AttachmentImportException.Unsupported(image.mimeType)
        }
        return image.path
    }

    private suspend fun deleteProfileImage(path: String) {
        val file = File(path)
        if (file.parentFile == profileImages.directory) {
            withContext(Dispatchers.IO) { profileImages.deleteFile(file.name) }
        }
    }

    private fun observeSettings(): Flow<Settings> {
        val allSettings = preferences.map { stored -> toSettings(stored) }
        val changedSettings = allSettings.distinctUntilChanged()
        // Reading the settings checks that the avatars' files exist
        return changedSettings.flowOn(Dispatchers.IO)
    }

    /** Checks that the avatars' files exist, so it runs off the main thread (DataStore edits do). */
    private fun toSettings(stored: Preferences): Settings {
        val defaults = Settings()
        return Settings(
            userName = stored[Keys.USER_NAME] ?: defaults.userName,
            userImage = profileImagePathOf(stored[Keys.USER_IMAGE]),
            assistantName = stored[Keys.ASSISTANT_NAME] ?: defaults.assistantName,
            assistantImage = profileImagePathOf(stored[Keys.ASSISTANT_IMAGE]),
            themeMode = enumOrDefault(stored[Keys.THEME_MODE], defaults.themeMode),
            accentColor = stored[Keys.ACCENT_COLOR] ?: defaults.accentColor,
            useSystemColors = stored[Keys.USE_SYSTEM_COLORS] ?: defaults.useSystemColors,
            wakeWordEnabled = stored[Keys.WAKE_WORD_ENABLED] ?: defaults.wakeWordEnabled,
            speakReplies = enumOrDefault(stored[Keys.SPEAK_REPLIES], defaults.speakReplies),
            downloadOnWifiOnly = stored[Keys.DOWNLOAD_ON_WIFI_ONLY] ?: defaults.downloadOnWifiOnly,
        )
    }

    /**
     * Avatars are stored by file name (see [isPlainFileName]); one whose file is gone (a cloud
     * backup leaves avatars out) reads as no avatar.
     */
    private fun profileImagePathOf(fileName: String?): String? {
        if (fileName == null || !isPlainFileName(fileName)) {
            return null
        }
        val file = File(profileImages.directory, fileName)
        return if (file.isFile) file.path else null
    }

    private fun profileImageNameOf(path: String?): String? {
        if (path == null) {
            return null
        }
        val file = File(path)
        require(file.parentFile == profileImages.directory) {
            "$path is not an avatar in app storage: use setUserImage or setAssistantImage"
        }
        return file.name
    }

    private fun writeChanges(stored: MutablePreferences, current: Settings, updated: Settings) {
        val currentUserImage = profileImageNameOf(current.userImage)
        val updatedUserImage = profileImageNameOf(updated.userImage)
        val currentAssistantImage = profileImageNameOf(current.assistantImage)
        val updatedAssistantImage = profileImageNameOf(updated.assistantImage)

        stored.writeChange(Keys.USER_NAME, current.userName, updated.userName)
        stored.writeChange(Keys.USER_IMAGE, currentUserImage, updatedUserImage)
        stored.writeChange(Keys.ASSISTANT_NAME, current.assistantName, updated.assistantName)
        stored.writeChange(Keys.ASSISTANT_IMAGE, currentAssistantImage, updatedAssistantImage)
        stored.writeChange(Keys.THEME_MODE, current.themeMode.name, updated.themeMode.name)
        stored.writeChange(Keys.ACCENT_COLOR, current.accentColor, updated.accentColor)
        stored.writeChange(Keys.USE_SYSTEM_COLORS, current.useSystemColors, updated.useSystemColors)
        stored.writeChange(Keys.WAKE_WORD_ENABLED, current.wakeWordEnabled, updated.wakeWordEnabled)
        stored.writeChange(Keys.SPEAK_REPLIES, current.speakReplies.name, updated.speakReplies.name)
        stored.writeChange(Keys.DOWNLOAD_ON_WIFI_ONLY, current.downloadOnWifiOnly, updated.downloadOnWifiOnly)
    }

    /** Writes [new] when it differs from [old]; null removes the key. */
    private fun <T> MutablePreferences.writeChange(key: Preferences.Key<T>, old: T?, new: T?) {
        if (old == new) {
            return
        }
        if (new == null) {
            remove(key)
        } else {
            set(key, new)
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T {
        val values = enumValues<T>()
        return values.firstOrNull { value -> value.name == name } ?: default
    }

    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_IMAGE = stringPreferencesKey("user_image")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val ASSISTANT_IMAGE = stringPreferencesKey("assistant_image")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val USE_SYSTEM_COLORS = booleanPreferencesKey("use_system_colors")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val SPEAK_REPLIES = stringPreferencesKey("speak_replies")
        val DOWNLOAD_ON_WIFI_ONLY = booleanPreferencesKey("download_on_wifi_only")
    }

    private companion object {
        const val PROFILE_DIRECTORY_NAME = "profile"
        const val PROFILE_IMAGE_TYPE_HINT = "image/*"
        const val PROFILE_IMAGE_BYTES = 20L * 1024 * 1024

        /** Every kind gets the image limit so the copy stops early; non-images are then refused. */
        val PROFILE_IMAGE_LIMITS = AttachmentLimits(
            maxImageBytes = PROFILE_IMAGE_BYTES,
            maxVideoBytes = PROFILE_IMAGE_BYTES,
            maxAudioBytes = PROFILE_IMAGE_BYTES,
        )
    }
}
