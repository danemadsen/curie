package com.danemadsen.curie.ui.setup

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.engine.ModelFiles
import com.danemadsen.curie.model.ModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the setup screen needs of the model's files. */
interface ModelDownloads {
    val state: StateFlow<ModelState>

    fun startDownload()

    fun cancelDownload()
}

/**
 * The setup screen's state holder: the model download's progress (with a speed estimate), its
 * failure, and the Wi-Fi-only choice.
 */
class SetupViewModel(
    private val downloads: ModelDownloads,
    settings: Flow<Settings>,
    private val setWifiOnly: suspend (Boolean) -> Unit,
    private val describeFailure: (ModelState.Failed) -> String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val rate = TransferRateEstimator()

    private val initialState = SetupUiState(
        downloadSizeBytes = ModelFiles.DOWNLOAD_SIZE,
        wifiOnly = Settings().downloadOnWifiOnly,
    )

    val uiState: StateFlow<SetupUiState> = combine(downloads.state, settings) { modelState, currentSettings ->
        SetupUiState(
            downloadSizeBytes = ModelFiles.DOWNLOAD_SIZE,
            wifiOnly = currentSettings.downloadOnWifiOnly,
            download = downloadStateOf(modelState),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState)

    /** True once every download is present and verified: the app can move on. */
    val isModelReady: StateFlow<Boolean> = downloads.state
        .map { state -> state == ModelState.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, downloads.state.value == ModelState.Ready)

    fun onAction(action: SetupAction) {
        when (action) {
            SetupAction.Download -> downloads.startDownload()
            SetupAction.CancelDownload -> downloads.cancelDownload()
            is SetupAction.SetWifiOnly -> viewModelScope.launch { setWifiOnly(action.wifiOnly) }
        }
    }

    private fun downloadStateOf(state: ModelState): DownloadState = when (state) {
        ModelState.NotDownloaded -> {
            rate.reset()
            DownloadState.Idle
        }
        is ModelState.Downloading -> {
            if (state.isQueued) {
                rate.reset()
                DownloadState.WaitingForNetwork
            } else {
                val bytesPerSecond = rate.update(clock(), state.doneBytes)
                DownloadState.Running(state.doneBytes, state.totalBytes, bytesPerSecond)
            }
        }
        ModelState.Verifying, ModelState.Ready -> DownloadState.Verifying
        is ModelState.Failed -> {
            rate.reset()
            DownloadState.Failed(describeFailure(state))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Download speed from progress readings: the bytes over the last [windowMs], reported once at
 * least [minimumSpanMs] of readings back it (null before, so a burst does not show as the speed).
 */
internal class TransferRateEstimator(
    private val windowMs: Long = 8_000,
    private val minimumSpanMs: Long = 2_000,
) {
    private class Reading(val timeMs: Long, val bytes: Long)

    private val readings = ArrayDeque<Reading>()

    /** Adds a reading of [bytes] done at [timeMs]; returns bytes per second, or null when unsure. */
    fun update(timeMs: Long, bytes: Long): Long? {
        val last = readings.lastOrNull()
        val hasRestarted = last != null && (bytes < last.bytes || timeMs < last.timeMs)
        if (hasRestarted) {
            readings.clear()
        }
        readings.addLast(Reading(timeMs, bytes))
        while (readings.size > 2 && timeMs - readings.first().timeMs > windowMs) {
            readings.removeFirst()
        }

        val first = readings.first()
        val spanMs = timeMs - first.timeMs
        if (spanMs < minimumSpanMs) {
            return null
        }
        val transferred = bytes - first.bytes
        return transferred * 1000 / spanMs
    }

    fun reset() {
        readings.clear()
    }
}
