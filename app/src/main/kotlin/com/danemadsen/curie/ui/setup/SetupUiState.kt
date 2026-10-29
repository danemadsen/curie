package com.danemadsen.curie.ui.setup

import androidx.compose.runtime.Immutable

/** The first-launch screen, shown until the model is on the device. */
@Immutable
data class SetupUiState(
    /** The size of the whole download, in bytes. */
    val downloadSizeBytes: Long,

    /** Download only over unmetered networks. */
    val wifiOnly: Boolean,

    val download: DownloadState = DownloadState.Idle,
)

/** Where the model download stands. */
@Immutable
sealed interface DownloadState {
    /** Not started (or cancelled). */
    data object Idle : DownloadState

    /** Started, but waiting for a network it may use (Wi-Fi when [SetupUiState.wifiOnly]). */
    data object WaitingForNetwork : DownloadState

    /** Downloading; [bytesPerSecond] is null until there is a reliable estimate. */
    data class Running(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long? = null,
    ) : DownloadState

    /** Downloaded; the files' checksums are being verified. */
    data object Verifying : DownloadState

    /** The download stopped; [message] says why, if known. */
    data class Failed(val message: String? = null) : DownloadState
}

/** What the user asks for on the setup screen. */
sealed interface SetupAction {
    /** Start the download, or retry it after a failure. */
    data object Download : SetupAction

    data object CancelDownload : SetupAction

    data class SetWifiOnly(val wifiOnly: Boolean) : SetupAction
}
