package com.danemadsen.curie.ui.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danemadsen.curie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The screens of the main activity. */
sealed interface Destination {
    /** The model download, until the model is on the device. */
    @Serializable
    data object Setup : Destination

    @Serializable
    data object Chat : Destination

    @Serializable
    data object Settings : Destination

    @Serializable
    data object Licenses : Destination
}

/** Media another app shared with Curie, waiting for the chat screen to take it. */
data class SharedMedia(val uris: List<Uri>, val mimeType: String?)

/**
 * The main activity's state holder: the look (theme and accent from the settings), the first
 * screen (decided from the files on disk) and the requests that arrive with intents (shared media,
 * opening the settings), held until the screens take them. The splash screen stays up until the
 * look and the first screen are known.
 */
class MainViewModel(
    settings: Flow<Settings>,
    private val hasCompleteDownload: () -> Boolean,
) : ViewModel() {
    private val mutableStartDestination = MutableStateFlow<Destination?>(null)
    private val mutableSharedMedia = MutableStateFlow<SharedMedia?>(null)
    private val mutableOpenSettings = MutableStateFlow(false)

    /** The settings the look comes from; null until they were read. */
    val appearance: StateFlow<Settings?> = settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the settings and the files were looked at. */
    val startDestination: StateFlow<Destination?> = mutableStartDestination.asStateFlow()

    val sharedMedia: StateFlow<SharedMedia?> = mutableSharedMedia.asStateFlow()

    val openSettings: StateFlow<Boolean> = mutableOpenSettings.asStateFlow()

    init {
        viewModelScope.launch {
            appearance.filterNotNull().first()
            val isDownloaded = withContext(Dispatchers.IO) { hasCompleteDownload() }
            mutableStartDestination.value = if (isDownloaded) Destination.Chat else Destination.Setup
        }
    }

    fun onSharedMedia(media: SharedMedia) {
        mutableSharedMedia.value = media
    }

    fun onOpenSettings() {
        mutableOpenSettings.value = true
    }

    /** The chat screen took the shared media. */
    fun consumeSharedMedia(): SharedMedia? {
        val media = mutableSharedMedia.value
        mutableSharedMedia.value = null
        return media
    }

    /** The settings screen was opened. */
    fun consumeOpenSettings() {
        mutableOpenSettings.value = false
    }
}
