package com.danemadsen.curie.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.ui.call.CallScreen
import com.danemadsen.curie.ui.chat.ChatScreen
import com.danemadsen.curie.ui.chat.Composer
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.components.ConfirmDialog
import com.danemadsen.curie.ui.components.Eclipse
import com.danemadsen.curie.ui.components.TypingIndicator
import com.danemadsen.curie.ui.settings.LicensesScreen
import com.danemadsen.curie.ui.settings.SettingsScreen
import com.danemadsen.curie.ui.setup.SetupScreen
import com.danemadsen.curie.ui.theme.CurieTheme

private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 915

@Preview(
    name = "Chat (dark)",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Chat (light)", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun ChatScreenPreview() {
    CurieTheme {
        ChatScreen(state = PreviewData.CHAT, prompt = rememberTextFieldState(), onAction = {})
    }
}

@Preview(
    name = "Chat, drawer open",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatDrawerPreview() {
    CurieTheme {
        ChatScreen(
            state = PreviewData.IDLE_CHAT,
            prompt = rememberTextFieldState(),
            onAction = {},
            drawerState = rememberDrawerState(DrawerValue.Open),
        )
    }
}

@Preview(
    name = "Chat, downloading",
    widthDp = PHONE_WIDTH,
    heightDp = 300,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatDownloadingPreview() {
    CurieTheme {
        val state = PreviewData.IDLE_CHAT.copy(
            modelStatus = ModelStatus.Downloading(progress = 0.42f),
            messages = emptyList(),
        )
        ChatScreen(state = state, prompt = rememberTextFieldState(), onAction = {})
    }
}

@Preview(name = "Composer states", widthDp = PHONE_WIDTH, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComposerPreview() {
    CurieTheme {
        val previewModifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)

        Column(modifier = previewModifier) {
            Composer(
                prompt = rememberTextFieldState(),
                state = PreviewData.COMPOSER_WITH_ATTACHMENTS,
                modelStatus = ModelStatus.Ready,
                isGenerating = false,
                onAction = {},
            )
            Composer(
                prompt = rememberTextFieldState("Tell me about Marie Curie"),
                state = PreviewData.IDLE_CHAT.composer,
                modelStatus = ModelStatus.Ready,
                isGenerating = false,
                onAction = {},
            )
            Composer(
                prompt = rememberTextFieldState(),
                state = PreviewData.IDLE_CHAT.composer,
                modelStatus = ModelStatus.Ready,
                isGenerating = true,
                onAction = {},
            )
            Composer(
                prompt = rememberTextFieldState(),
                state = PreviewData.COMPOSER_RECORDING,
                modelStatus = ModelStatus.Ready,
                isGenerating = false,
                onAction = {},
            )
        }
    }
}

@Preview(
    name = "Call (dark)",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Call (light)", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CallScreenPreview() {
    CurieTheme {
        CallScreen(state = PreviewData.CALL, onAction = {})
    }
}

@Preview(
    name = "Settings (dark)",
    widthDp = PHONE_WIDTH,
    heightDp = 1800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Settings (light)", widthDp = PHONE_WIDTH, heightDp = 1800)
@Composable
private fun SettingsScreenPreview() {
    CurieTheme {
        SettingsScreen(state = PreviewData.SETTINGS, onAction = {})
    }
}

@Preview(
    name = "Licenses",
    widthDp = PHONE_WIDTH,
    heightDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LicensesScreenPreview() {
    CurieTheme {
        LicensesScreen(licenses = PreviewData.LICENSES, onBack = {})
    }
}

@Preview(
    name = "Setup",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SetupScreenPreview() {
    CurieTheme {
        SetupScreen(state = PreviewData.SETUP, onAction = {})
    }
}

@Preview(name = "Setup, downloading", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun SetupRunningPreview() {
    CurieTheme {
        SetupScreen(state = PreviewData.SETUP_RUNNING, onAction = {})
    }
}

@Preview(
    name = "Setup, failed",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SetupFailedPreview() {
    CurieTheme {
        SetupScreen(state = PreviewData.SETUP_FAILED, onAction = {})
    }
}

@Preview(
    name = "Confirm dialog",
    widthDp = PHONE_WIDTH,
    heightDp = 500,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConfirmDialogPreview() {
    CurieTheme {
        ConfirmDialog(
            message = "Are you sure you want to clear all chats? This action cannot be undone.",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "Typing indicator and eclipse",
    widthDp = PHONE_WIDTH,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArtPreview() {
    CurieTheme {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            TypingIndicator()
            Box(modifier = Modifier.padding(24.dp)) {
                Eclipse(modifier = Modifier.size(200.dp))
            }
        }
    }
}
