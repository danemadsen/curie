package com.danemadsen.curie.ui.common

import androidx.compose.runtime.Immutable

/** A participant of the chat as the UI shows it: the user or the assistant. */
@Immutable
data class Persona(
    val name: String,

    /** The avatar: a content:// or file:// URI or a file path; null draws the default icon. */
    val imageUri: String? = null,
)

/** Where the one on-device model stands. Drives the chat header pill, the call button and settings. */
@Immutable
sealed interface ModelStatus {
    /** The model files are not on the device. */
    data object NotDownloaded : ModelStatus

    /** The files are downloading; [progress] runs from 0 to 1, null while the size is unknown. */
    data class Downloading(val progress: Float?) : ModelStatus

    /** The files are on the device and the engine is starting. */
    data object Loading : ModelStatus

    /** The engine is ready for chats and calls. */
    data object Ready : ModelStatus

    /**
     * Downloading or loading failed. [message] says why, if known; [filesOnDevice] is true when the
     * download had finished (loading failed), so the files can be deleted or downloaded again.
     */
    data class Failed(val message: String? = null, val filesOnDevice: Boolean = false) : ModelStatus
}

/** True when the downloaded files are on the device, whether or not the engine runs. */
val ModelStatus.hasModelFiles: Boolean
    get() = when (this) {
        ModelStatus.Loading, ModelStatus.Ready -> true
        is ModelStatus.Failed -> filesOnDevice
        ModelStatus.NotDownloaded, is ModelStatus.Downloading -> false
    }
