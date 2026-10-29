package com.danemadsen.curie.ui.settings

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.hasModelFiles
import com.danemadsen.curie.ui.components.AssistantAvatar
import com.danemadsen.curie.ui.components.ConfirmDialog
import com.danemadsen.curie.ui.components.Dropdown
import com.danemadsen.curie.ui.components.DropdownItem
import com.danemadsen.curie.ui.components.HueSlider
import com.danemadsen.curie.ui.components.PillButton
import com.danemadsen.curie.ui.components.PillTextField
import com.danemadsen.curie.ui.components.ScreenHeader
import com.danemadsen.curie.ui.components.SectionDivider
import com.danemadsen.curie.ui.components.UserAvatar
import com.danemadsen.curie.ui.theme.ThemeMode
import kotlin.math.roundToInt

private val AVATAR_SIZE = 80.dp
private val NAME_FIELD_WIDTH = 300.dp
private val GROUP_SPACING = 16.dp

/**
 * Maid's settings page: groups separated by 2 dp dividers, centred titles, pill buttons and pill
 * inputs. Curie's groups: the user, the assistant, the voice assistant, the theme, the model and
 * About. Names are committed when their field loses focus or the keyboard's Done is pressed.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val screenModifier = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .windowInsetsPadding(horizontalInsets)

    Column(modifier = screenModifier) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            onBack = { onAction(SettingsAction.Back) },
        )

        // The keyboard shrinks the scrolling area, which scrolls a focused name field into view
        // above it; inside, the content ends clear of the navigation bar while the keyboard is shut
        val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        val contentModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(bottomInsets)
            .padding(top = 8.dp, bottom = 16.dp)

        Column(modifier = contentModifier) {
            UserGroup(state = state, onAction = onAction)
            SectionDivider()
            AssistantGroup(state = state, onAction = onAction)
            SectionDivider()
            VoiceAssistantGroup(
                state = state.voiceAssistant,
                readAloud = state.readAloud,
                assistantName = state.assistant.name,
                onAction = onAction,
            )
            SectionDivider()
            ThemeGroup(state = state, onAction = onAction)
            SectionDivider()
            ModelGroup(status = state.modelStatus, sizeBytes = state.modelSizeBytes, onAction = onAction)
            SectionDivider()
            AboutGroup(appVersion = state.appVersion, onAction = onAction)
        }
    }
}

/** A Maid settings group: its items centred and 16 dp apart, under a bold [title] if it has one. */
@Composable
private fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val groupModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)

    Column(
        modifier = groupModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GROUP_SPACING),
    ) {
        if (title != null) {
            GroupTitle(text = title)
        }
        content()
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun UserGroup(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_user_title)) {
        UserAvatar(user = state.user, size = AVATAR_SIZE)
        PillButton(
            text = stringResource(R.string.settings_load_user_image),
            onClick = { onAction(SettingsAction.PickUserImage) },
        )
        PillTextField(
            value = state.user.name,
            onValueCommit = { name -> onAction(SettingsAction.SetUserName(name)) },
            placeholder = stringResource(R.string.settings_user_name),
            modifier = Modifier.width(NAME_FIELD_WIDTH),
        )
    }
}

@Composable
private fun AssistantGroup(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_assistant_title)) {
        AssistantAvatar(assistant = state.assistant, size = AVATAR_SIZE)
        PillButton(
            text = stringResource(R.string.settings_load_assistant_image),
            onClick = { onAction(SettingsAction.PickAssistantImage) },
        )
        PillTextField(
            value = state.assistant.name,
            onValueCommit = { name -> onAction(SettingsAction.SetAssistantName(name)) },
            placeholder = stringResource(R.string.settings_assistant_name),
            modifier = Modifier.width(NAME_FIELD_WIDTH),
        )
    }
}

/**
 * When replies are read aloud, the wake word switch and its explanation, the default assistant
 * role, and what is missing for them: the microphone, and on Android 13 and later the
 * notification that shows Curie listening.
 */
@Composable
private fun VoiceAssistantGroup(
    state: VoiceAssistantUiState,
    readAloud: ReadAloud,
    assistantName: String,
    onAction: (SettingsAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val readAloudItems = listOf(
        DropdownItem(stringResource(R.string.settings_read_aloud_never), ReadAloud.NEVER),
        DropdownItem(stringResource(R.string.settings_read_aloud_voice_messages), ReadAloud.VOICE_MESSAGES),
        DropdownItem(stringResource(R.string.settings_read_aloud_always), ReadAloud.ALWAYS),
    )
    val readAloudLabel = stringResource(R.string.settings_read_aloud)

    SettingsGroup(title = stringResource(R.string.settings_voice_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = readAloudLabel, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Dropdown(
                items = readAloudItems,
                selectedValue = readAloud,
                onValueChange = { selected -> onAction(SettingsAction.SetReadAloud(selected)) },
                contentDescription = readAloudLabel,
            )
        }

        val switchRowModifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.isWakeWordEnabled,
                role = Role.Switch,
                onValueChange = { enabled -> onAction(SettingsAction.SetWakeWordEnabled(enabled)) },
            )

        Row(modifier = switchRowModifier, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_wake_word),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_wake_word_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // The row toggles; the switch only shows the state
            Switch(
                checked = state.isWakeWordEnabled,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        if (state.isDefaultAssistant) {
            StatusLine(
                icon = R.drawable.tabler_circle_check_filled,
                iconTint = colors.primary,
                text = stringResource(R.string.settings_is_default_assistant, assistantName),
            )
        } else {
            PillButton(
                text = stringResource(R.string.settings_set_default_assistant),
                onClick = { onAction(SettingsAction.BecomeDefaultAssistant) },
            )
        }

        if (!state.isMicrophoneGranted) {
            PermissionHint(
                text = stringResource(R.string.settings_microphone_needed),
                onClick = { onAction(SettingsAction.RequestPermission(AppPermission.MICROPHONE)) },
            )
        }
        if (!state.isNotificationGranted) {
            PermissionHint(
                text = stringResource(R.string.settings_notifications_needed),
                onClick = { onAction(SettingsAction.RequestPermission(AppPermission.NOTIFICATIONS)) },
            )
        }
    }
}

/** A missing permission: what it is needed for, and a tap to allow it. */
@Composable
private fun PermissionHint(text: String, onClick: () -> Unit) {
    val allowLabel = stringResource(R.string.settings_allow)
    val hintModifier = Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = allowLabel, role = Role.Button, onClick = onClick)

    Row(modifier = hintModifier, verticalAlignment = Alignment.CenterVertically) {
        StatusLine(
            icon = R.drawable.tabler_alert_circle_filled,
            iconTint = MaterialTheme.colorScheme.error,
            text = text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = allowLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun StatusLine(
    @DrawableRes icon: Int,
    iconTint: Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Maid's ThemeSettingsGroup: the mode dropdown and the accent colour's hue slider, plus the switch
 * to the system's colours where the phone has them (the slider shows only when it has an effect).
 */
@Composable
private fun ThemeGroup(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    val themeItems = listOf(
        DropdownItem(stringResource(R.string.settings_theme_system), ThemeMode.SYSTEM),
        DropdownItem(stringResource(R.string.settings_theme_light), ThemeMode.LIGHT),
        DropdownItem(stringResource(R.string.settings_theme_dark), ThemeMode.DARK),
    )
    val themeLabel = stringResource(R.string.settings_theme)
    val colors = MaterialTheme.colorScheme

    SettingsGroup(title = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = themeLabel, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Dropdown(
                items = themeItems,
                selectedValue = state.themeMode,
                onValueChange = { mode -> onAction(SettingsAction.SetThemeMode(mode)) },
                contentDescription = themeLabel,
            )
        }

        if (state.areSystemColorsAvailable) {
            SystemColorsSwitch(
                isEnabled = state.useSystemColors,
                onChange = { enabled -> onAction(SettingsAction.SetUseSystemColors(enabled)) },
            )
        }

        val isAccentUsed = !state.areSystemColorsAvailable || !state.useSystemColors
        if (isAccentUsed) {
            val accentLabel = stringResource(R.string.settings_accent_color)
            GroupTitle(text = accentLabel)
            HueSlider(
                color = state.accentColor,
                onColorChange = { color -> onAction(SettingsAction.SetAccentColor(color)) },
                contentDescription = accentLabel,
            )
        }
    }
}

/** Colouring the app from the system's palette (Material You) instead of the accent colour. */
@Composable
private fun SystemColorsSwitch(isEnabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowModifier = Modifier
        .fillMaxWidth()
        .toggleable(value = isEnabled, role = Role.Switch, onValueChange = onChange)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_system_colors),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_system_colors_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // The row toggles; the switch only shows the state
        Switch(
            checked = isEnabled,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The model's state and size, and what can be done with it right now. */
@Composable
private fun ModelGroup(status: ModelStatus, sizeBytes: Long, onAction: (SettingsAction) -> Unit) {
    var isDeleteConfirmShown by rememberSaveable { mutableStateOf(false) }
    var isRedownloadConfirmShown by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val sizeText = Formatter.formatShortFileSize(context, sizeBytes)

    SettingsGroup(title = stringResource(R.string.settings_model_title)) {
        InfoRow(label = stringResource(R.string.settings_model_status), value = modelStatusText(status))
        InfoRow(label = stringResource(R.string.settings_model_size), value = sizeText)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                status is ModelStatus.Downloading -> PillButton(
                    text = stringResource(R.string.settings_cancel_download),
                    onClick = { onAction(SettingsAction.CancelDownload) },
                )
                status.hasModelFiles -> {
                    PillButton(
                        text = stringResource(R.string.settings_delete_model),
                        onClick = { isDeleteConfirmShown = true },
                    )
                    PillButton(
                        text = stringResource(R.string.settings_redownload_model),
                        onClick = { isRedownloadConfirmShown = true },
                    )
                }
                else -> PillButton(
                    text = stringResource(R.string.settings_download_model, sizeText),
                    onClick = { onAction(SettingsAction.DownloadModel) },
                )
            }
        }
    }

    if (isDeleteConfirmShown) {
        ConfirmDialog(
            message = stringResource(R.string.settings_delete_model_confirm, sizeText),
            onConfirm = {
                isDeleteConfirmShown = false
                onAction(SettingsAction.DeleteModel)
            },
            onDismiss = { isDeleteConfirmShown = false },
        )
    }
    if (isRedownloadConfirmShown) {
        ConfirmDialog(
            message = stringResource(R.string.settings_redownload_model_confirm, sizeText),
            onConfirm = {
                isRedownloadConfirmShown = false
                onAction(SettingsAction.RedownloadModel)
            },
            onDismiss = { isRedownloadConfirmShown = false },
        )
    }
}

@Composable
private fun modelStatusText(status: ModelStatus): String = when (status) {
    ModelStatus.NotDownloaded -> stringResource(R.string.settings_model_not_downloaded)
    is ModelStatus.Downloading -> {
        val progress = status.progress
        if (progress == null) {
            stringResource(R.string.model_status_downloading)
        } else {
            val percent = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
            stringResource(R.string.model_status_downloading_percent, percent)
        }
    }
    ModelStatus.Loading -> stringResource(R.string.model_status_loading)
    ModelStatus.Ready -> stringResource(R.string.settings_model_ready)
    is ModelStatus.Failed -> {
        val message = status.message
        if (message.isNullOrBlank()) {
            stringResource(R.string.model_status_error)
        } else {
            stringResource(R.string.settings_model_error, message)
        }
    }
}

/** Maid's About rows: a label on the left and its value in the outline colour on the right. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
    }
}

@Composable
private fun AboutGroup(appVersion: String, onAction: (SettingsAction) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_about_title)) {
        InfoRow(label = stringResource(R.string.settings_app_version), value = appVersion)
        PillButton(
            text = stringResource(R.string.settings_licenses),
            onClick = { onAction(SettingsAction.OpenLicenses) },
        )
    }
}
