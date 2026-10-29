package com.danemadsen.curie.ui.setup

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.components.Eclipse
import com.danemadsen.curie.ui.components.PillButton
import com.danemadsen.curie.ui.components.PillButtonStyle
import kotlin.math.roundToInt

private val CONTENT_MAX_WIDTH = 480.dp
private val PROGRESS_HEIGHT = 6.dp

/**
 * The first launch, before the model is on the device: the eclipse, what Curie is, and the one
 * download it needs, with a Wi-Fi-only choice, progress (bytes, percent and speed), cancel, and
 * the error with a retry when it fails.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenModifier = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .windowInsetsPadding(WindowInsets.safeDrawing)

    // Centred on the screen while it fits, scrolling when it does not (small screens, large fonts)
    BoxWithConstraints(modifier = screenModifier) {
        val columnModifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 24.dp, vertical = 16.dp)

        Column(
            modifier = columnModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val contentModifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()

            Column(modifier = contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Eclipse(modifier = Modifier.size(200.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Introduction()
                Spacer(modifier = Modifier.height(32.dp))
                DownloadSection(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun Introduction() {
    val colors = MaterialTheme.colorScheme

    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge,
        color = colors.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.setup_tagline),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )

    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Feature(icon = R.drawable.tabler_microphone, text = stringResource(R.string.setup_feature_voice))
        Feature(icon = R.drawable.tabler_photo, text = stringResource(R.string.setup_feature_media))
        Feature(icon = R.drawable.tabler_lock, text = stringResource(R.string.setup_feature_private))
    }
}

@Composable
private fun Feature(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The download button, or the download's progress, or its failure. */
@Composable
private fun DownloadSection(state: SetupUiState, onAction: (SetupAction) -> Unit) {
    val context = LocalContext.current
    val sizeText = Formatter.formatShortFileSize(context, state.downloadSizeBytes)
    val download = state.download

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (download) {
            DownloadState.Idle -> {
                PillButton(
                    text = stringResource(R.string.setup_download, sizeText),
                    onClick = { onAction(SetupAction.Download) },
                    style = PillButtonStyle.FILLED,
                )
                WifiOnlyCheckbox(state.wifiOnly, onAction)
            }
            DownloadState.WaitingForNetwork -> {
                val waitingText = if (state.wifiOnly) {
                    stringResource(R.string.setup_waiting_for_wifi)
                } else {
                    stringResource(R.string.setup_waiting_for_network)
                }
                DownloadProgress(fraction = null, description = waitingText, announce = true)
                WifiOnlyCheckbox(state.wifiOnly, onAction)
                CancelButton(onAction)
            }
            is DownloadState.Running -> {
                val fraction = if (download.totalBytes > 0L) {
                    (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                } else {
                    null
                }
                val description = progressText(context, download, fraction)
                DownloadProgress(fraction = fraction, description = description, announce = false)
                CancelButton(onAction)
            }
            DownloadState.Verifying -> {
                val verifyingText = stringResource(R.string.setup_verifying)
                DownloadProgress(fraction = null, description = verifyingText, announce = true)
            }
            is DownloadState.Failed -> {
                DownloadFailure(message = download.message)
                PillButton(
                    text = stringResource(R.string.setup_retry),
                    onClick = { onAction(SetupAction.Download) },
                    style = PillButtonStyle.FILLED,
                )
                WifiOnlyCheckbox(state.wifiOnly, onAction)
            }
        }
    }
}

/** "1.2 GB of 2.9 GB · 42 % · 12 MB/s", leaving out what is not known yet. */
@Composable
private fun progressText(context: Context, download: DownloadState.Running, fraction: Float?): String {
    val downloaded = Formatter.formatShortFileSize(context, download.downloadedBytes)
    val total = Formatter.formatShortFileSize(context, download.totalBytes)
    val amount = stringResource(R.string.setup_progress_bytes, downloaded, total)
    val parts = mutableListOf(amount)

    if (fraction != null) {
        val percent = (fraction * 100f).roundToInt()
        parts.add(stringResource(R.string.setup_progress_percent, percent))
    }

    val bytesPerSecond = download.bytesPerSecond
    if (bytesPerSecond != null && bytesPerSecond > 0L) {
        val speed = Formatter.formatShortFileSize(context, bytesPerSecond)
        parts.add(stringResource(R.string.setup_progress_speed, speed))
    }

    val separator = stringResource(R.string.setup_progress_separator)
    return parts.joinToString(separator)
}

/**
 * A rounded bar that fills with [fraction], or moves back and forth while it is null, above its
 * [description]. With [announce] the description is a polite live region, which screen readers
 * announce when it changes: meant for the download's phases, not for its changing numbers (the
 * bar itself reports the progress).
 */
@Composable
private fun DownloadProgress(fraction: Float?, description: String, announce: Boolean) {
    val colors = MaterialTheme.colorScheme
    val barModifier = Modifier
        .fillMaxWidth()
        .height(PROGRESS_HEIGHT)

    if (fraction == null) {
        LinearProgressIndicator(
            modifier = barModifier,
            color = colors.primary,
            trackColor = colors.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = barModifier,
            color = colors.primary,
            trackColor = colors.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }

    val descriptionModifier = if (announce) {
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    } else {
        Modifier
    }

    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = descriptionModifier,
    )
}

@Composable
private fun DownloadFailure(message: String?) {
    val colors = MaterialTheme.colorScheme
    val text = if (message.isNullOrBlank()) {
        stringResource(R.string.setup_failed)
    } else {
        stringResource(R.string.setup_failed_reason, message)
    }

    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.tabler_alert_triangle),
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.error)
    }
}

@Composable
private fun CancelButton(onAction: (SetupAction) -> Unit) {
    PillButton(
        text = stringResource(R.string.setup_cancel),
        onClick = { onAction(SetupAction.CancelDownload) },
    )
}

@Composable
private fun WifiOnlyCheckbox(wifiOnly: Boolean, onAction: (SetupAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowModifier = Modifier.toggleable(
        value = wifiOnly,
        role = Role.Checkbox,
        onValueChange = { checked -> onAction(SetupAction.SetWifiOnly(checked)) },
    )

    val checkboxColors = CheckboxDefaults.colors(
        checkedColor = colors.primary,
        uncheckedColor = colors.onSurfaceVariant,
        checkmarkColor = colors.onPrimary,
    )

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = wifiOnly, onCheckedChange = null, colors = checkboxColors)
        Text(
            text = stringResource(R.string.setup_wifi_only),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
