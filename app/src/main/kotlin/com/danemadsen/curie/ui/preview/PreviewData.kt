package com.danemadsen.curie.ui.preview

import com.danemadsen.curie.ui.call.CallPhase
import com.danemadsen.curie.ui.call.CallUiState
import com.danemadsen.curie.ui.chat.AttachmentUi
import com.danemadsen.curie.ui.chat.AudioClipUi
import com.danemadsen.curie.ui.chat.AudioPlayback
import com.danemadsen.curie.ui.chat.ChatSummary
import com.danemadsen.curie.ui.chat.ChatUiState
import com.danemadsen.curie.ui.chat.ComposerUiState
import com.danemadsen.curie.ui.chat.MessageRole
import com.danemadsen.curie.ui.chat.MessageUi
import com.danemadsen.curie.ui.chat.RecordingUi
import com.danemadsen.curie.ui.chat.ToolActionUi
import com.danemadsen.curie.ui.chat.ToolIcon
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.settings.LicenseUi
import com.danemadsen.curie.ui.settings.ReadAloud
import com.danemadsen.curie.ui.settings.SettingsUiState
import com.danemadsen.curie.ui.settings.VoiceAssistantUiState
import com.danemadsen.curie.ui.setup.DownloadState
import com.danemadsen.curie.ui.setup.SetupUiState
import com.danemadsen.curie.ui.theme.DEFAULT_ACCENT_COLOR
import com.danemadsen.curie.ui.theme.ThemeMode

/** Made-up states for previews and screenshots. */
object PreviewData {
    /** The size of the q4 model bundle (ModelFiles.DOWNLOAD_SIZE). */
    const val MODEL_SIZE_BYTES = 2_926_299_950L

    val USER = Persona(name = "Dane")
    val ASSISTANT = Persona(name = "Curie")

    val CHATS = listOf(
        ChatSummary(id = "chat-1", title = "Plant identification"),
        ChatSummary(id = "chat-2", title = "Weekend in Hobart"),
        ChatSummary(id = "chat-3", title = ""),
        ChatSummary(id = "chat-4", title = "Sourdough starter troubleshooting and feeding schedule"),
        ChatSummary(id = "chat-5", title = "Birthday ideas"),
    )

    private val ANSWER_MARKDOWN = """
        That looks like a **Monstera deliciosa**, often called the Swiss cheese plant.

        ### How to care for it
        - Bright, indirect light; direct sun scorches the leaves
        - Water when the top 5 cm of soil is dry
        - Wipe the leaves now and then so they can breathe

        > The holes in the leaves (*fenestrations*) appear as the plant matures.

        A quick check: `soil moisture < 30%` means it is time to water.
    """.trimIndent()

    private val REPLY_CLIP = AudioClipUi(
        id = "clip-reply",
        durationMillis = 7_400L,
        waveform = listOf(0.1f, 0.4f, 0.8f, 0.6f, 0.9f, 0.3f, 0.5f, 0.7f, 0.2f, 0.6f, 0.4f, 0.1f),
    )

    private val VOICE_CLIP = AudioClipUi(id = "clip-voice", durationMillis = 4_200L)

    val MESSAGES = listOf(
        MessageUi(
            id = "message-1",
            role = MessageRole.USER,
            text = "What plant is this?",
            attachments = listOf(
                AttachmentUi.Image(id = "image-1", uri = "", width = 1200, height = 900),
            ),
        ),
        MessageUi(
            id = "message-2",
            role = MessageRole.ASSISTANT,
            text = ANSWER_MARKDOWN,
            siblingIndex = 1,
            siblingCount = 2,
        ),
        MessageUi(
            id = "message-3",
            role = MessageRole.USER,
            text = "",
            attachments = listOf(AttachmentUi.Audio(VOICE_CLIP)),
        ),
        MessageUi(
            id = "message-4",
            role = MessageRole.ASSISTANT,
            text = "Done! YouTube is open, and I set a timer for ten minutes.",
            toolActions = listOf(
                ToolActionUi(id = "tool-1", icon = ToolIcon.APP, summary = "Opened YouTube"),
                ToolActionUi(id = "tool-2", icon = ToolIcon.TIMER, summary = "Timer set for 10 minutes"),
            ),
            spokenReply = REPLY_CLIP,
        ),
        MessageUi(
            id = "message-5",
            role = MessageRole.USER,
            text = "Thanks! Can you remind me what you said about watering?",
        ),
        MessageUi(id = "message-6", role = MessageRole.ASSISTANT, text = ""),
    )

    val CHAT = ChatUiState(
        modelStatus = ModelStatus.Ready,
        user = USER,
        assistant = ASSISTANT,
        chats = CHATS,
        activeChatId = "chat-1",
        messages = MESSAGES,
        isGenerating = true,
        audioPlayback = AudioPlayback(clipId = REPLY_CLIP.id, isPlaying = true, positionMillis = 3_100L),
    )

    val IDLE_CHAT = CHAT.copy(
        messages = MESSAGES.dropLast(1),
        isGenerating = false,
        audioPlayback = null,
    )

    val COMPOSER_ATTACHMENTS = listOf(
        AttachmentUi.Image(id = "pending-image", uri = "", width = 800, height = 800),
        AttachmentUi.Video(id = "pending-video", uri = "", durationMillis = 12_000L),
        AttachmentUi.Audio(AudioClipUi(id = "pending-audio", durationMillis = 3_000L)),
    )

    val COMPOSER_WITH_ATTACHMENTS = ComposerUiState(attachments = COMPOSER_ATTACHMENTS)

    val COMPOSER_RECORDING = ComposerUiState(recording = RecordingUi(elapsedMillis = 7_000L))

    val CALL = CallUiState(
        assistant = ASSISTANT,
        phase = CallPhase.SPEAKING,
        elapsedSeconds = 83L,
        userCaption = "What's the weather going to be like tomorrow afternoon?",
        assistantCaption = "Tomorrow afternoon looks mild: around nineteen degrees with a light breeze, " +
            "and the rain should clear by lunchtime.",
    )

    val SETTINGS = SettingsUiState(
        user = USER,
        assistant = ASSISTANT,
        voiceAssistant = VoiceAssistantUiState(
            isWakeWordEnabled = true,
            isDefaultAssistant = false,
            isMicrophoneGranted = true,
            isNotificationGranted = false,
        ),
        readAloud = ReadAloud.VOICE_MESSAGES,
        themeMode = ThemeMode.SYSTEM,
        accentColor = DEFAULT_ACCENT_COLOR,
        useSystemColors = true,
        areSystemColorsAvailable = true,
        modelStatus = ModelStatus.Ready,
        modelSizeBytes = MODEL_SIZE_BYTES,
        appVersion = "1.0.0 (412)",
    )

    val SETUP = SetupUiState(downloadSizeBytes = MODEL_SIZE_BYTES, wifiOnly = true)

    val SETUP_RUNNING = SETUP.copy(
        download = DownloadState.Running(
            downloadedBytes = 1_234_567_890L,
            totalBytes = MODEL_SIZE_BYTES,
            bytesPerSecond = 12_300_000L,
        ),
    )

    val SETUP_FAILED = SETUP.copy(download = DownloadState.Failed(message = "The connection was lost."))

    val LICENSES = listOf(
        LicenseUi(
            name = "onnxruntime",
            textBlocks = listOf("MIT License\n\nCopyright (c) Microsoft Corporation"),
        ),
        LicenseUi(
            name = "tabler-icons",
            textBlocks = listOf("MIT License\n\nCopyright (c) 2020-2026 Paweł Kuna"),
        ),
    )
}
