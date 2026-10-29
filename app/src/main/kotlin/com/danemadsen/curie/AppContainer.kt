package com.danemadsen.curie

import android.app.Application
import android.text.format.Formatter
import androidx.lifecycle.SavedStateHandle
import com.danemadsen.curie.assistant.WakeWordController
import com.danemadsen.curie.assistant.WakeWordPreferences
import com.danemadsen.curie.audio.AudioRecorder
import com.danemadsen.curie.audio.ClipPlayer
import com.danemadsen.curie.audio.MicrophoneArbiter
import com.danemadsen.curie.audio.SpeechPlayer
import com.danemadsen.curie.call.CallManager
import com.danemadsen.curie.data.AttachmentLimits
import com.danemadsen.curie.data.AttachmentStore
import com.danemadsen.curie.data.ChatRepository
import com.danemadsen.curie.data.ChatTransfer
import com.danemadsen.curie.data.CurieDatabase
import com.danemadsen.curie.data.SettingsRepository
import com.danemadsen.curie.engine.ContextOverflowException
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.inference.AndroidMediaInputs
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.inference.AssistantToolExecutor
import com.danemadsen.curie.inference.ChatHistory
import com.danemadsen.curie.inference.ChatResponder
import com.danemadsen.curie.inference.EngineUnavailableException
import com.danemadsen.curie.inference.ModelSource
import com.danemadsen.curie.inference.NotEnoughMemoryException
import com.danemadsen.curie.inference.OnnxEngineFactory
import com.danemadsen.curie.inference.PromptTooLongException
import com.danemadsen.curie.inference.SystemPrompts
import com.danemadsen.curie.inference.TokenizerRendererFactory
import com.danemadsen.curie.inference.ToolExecutor
import com.danemadsen.curie.model.ModelDownloader
import com.danemadsen.curie.model.ModelFailure
import com.danemadsen.curie.model.ModelRepository
import com.danemadsen.curie.model.ModelState
import com.danemadsen.curie.tools.AssistantTools
import com.danemadsen.curie.ui.call.CallViewModel
import com.danemadsen.curie.ui.chat.ChatDependencies
import com.danemadsen.curie.ui.chat.ChatViewModel
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.navigation.modelStatusOf
import com.danemadsen.curie.ui.settings.ModelControls
import com.danemadsen.curie.ui.settings.SettingsViewModel
import com.danemadsen.curie.ui.setup.SetupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.time.LocalDate

/**
 * The app's objects, made once per process (manual dependency injection): storage, the model and
 * its engine, audio, the assistant's tools, calls, and the factories of the screens' ViewModels.
 * Created in [CurieApplication.onCreate]; anything heavy is made on first use.
 */
class AppContainer(private val application: Application) {
    /** Outlives every screen; a failure in one task does not cancel the others. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Longer media can never fit the 4,096-position context: audio costs 12.5 tokens a second
     * (a video's soundtrack too), on top of a video's frames.
     */
    val attachmentLimits = AttachmentLimits(
        maxVideoDurationMs = MAX_VIDEO_DURATION_MS,
        maxAudioDurationMs = MAX_AUDIO_DURATION_MS,
    )

    val database: CurieDatabase by lazy { CurieDatabase.create(application) }
    val attachmentStore: AttachmentStore by lazy { AttachmentStore(application, attachmentLimits) }
    val chatRepository: ChatRepository by lazy { ChatRepository(application, database, attachmentStore) }
    val chatTransfer: ChatTransfer by lazy { ChatTransfer(chatRepository, attachmentStore) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(application) }
    val modelRepository: ModelRepository by lazy { ModelRepository(application, settingsRepository, applicationScope) }

    val microphoneArbiter = MicrophoneArbiter()
    val audioRecorder: AudioRecorder by lazy { AudioRecorder(application) }

    val toolExecutor: ToolExecutor by lazy { AssistantToolExecutor(AssistantTools(application)) }

    val engine: AssistantEngine by lazy { createEngine() }

    /** Where the one model stands, from its files and its engine, for every screen. */
    val modelStatus: StateFlow<ModelStatus> by lazy {
        val statuses = combine(modelRepository.state, engine.state) { model, engine ->
            modelStatusOf(model, engine, ::describeModelFailure)
        }
        statuses.stateIn(applicationScope, SharingStarted.Eagerly, ModelStatus.Loading)
    }

    val wakeWordController: WakeWordController by lazy {
        val preferences = SettingsWakeWordPreferences(settingsRepository)
        WakeWordController(application, preferences, microphoneArbiter)
    }

    val callManager: CallManager by lazy {
        CallManager(
            context = application,
            engine = engine,
            tools = toolExecutor,
            microphone = audioRecorder,
            arbiter = microphoneArbiter,
            settings = settingsRepository.settings,
            scope = applicationScope,
        )
    }

    private val modelControls: ModelControls by lazy { RepositoryModelControls(modelRepository) }

    // === ViewModels ===

    fun chatViewModel(savedState: SavedStateHandle): ChatViewModel {
        val history = ChatHistory(AndroidMediaInputs())
        val responder = ChatResponder(
            chats = chatRepository,
            engine = engine,
            tools = toolExecutor,
            history = history,
            describeError = ::describeAnswerError,
            skippedToolSummary = application.getString(R.string.tool_skipped),
            today = LocalDate::now,
        )
        val dependencies = ChatDependencies(
            context = application,
            chats = chatRepository,
            transfer = chatTransfer,
            attachments = attachmentStore,
            attachmentLimits = attachmentLimits,
            settings = settingsRepository.settings,
            modelStatus = modelStatus,
            engine = engine,
            responder = responder,
            microphone = audioRecorder,
            arbiter = microphoneArbiter,
            createClipPlayer = { scope -> ClipPlayer(application, scope) },
            createSpeechOutput = { SpeechPlayer(application, holdsFocus = true) },
        )
        return ChatViewModel(dependencies, savedState)
    }

    fun setupViewModel(): SetupViewModel = SetupViewModel(
        downloads = modelControls,
        settings = settingsRepository.settings,
        setWifiOnly = ::setDownloadOnWifiOnly,
        describeFailure = ::describeModelFailure,
    )

    fun settingsViewModel(): SettingsViewModel = SettingsViewModel(
        context = application,
        settingsRepository = settingsRepository,
        modelStatus = modelStatus,
        modelControls = modelControls,
        engine = engine,
        wakeWord = wakeWordController,
    )

    fun callViewModel(): CallViewModel = CallViewModel(callManager, settingsRepository.settings)

    /** True when the downloads are complete and verified (read from disk). */
    fun hasCompleteDownload(): Boolean {
        val directory = modelRepository.modelDirectory
        return ModelDownloader.isComplete(directory)
    }

    // === Helpers ===

    private fun createEngine(): AssistantEngine {
        val source = object : ModelSource {
            override val modelDirectory: File
                get() = modelRepository.modelDirectory

            override suspend fun ensureReady(): Boolean = modelRepository.ensureReady()
        }
        val engine = AssistantEngine(
            model = source,
            engineFactory = OnnxEngineFactory,
            rendererFactory = TokenizerRendererFactory,
            scope = applicationScope,
        )
        engine.chatWarmUpPrompt = ::chatWarmUpPrompt
        return engine
    }

    private suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) {
        settingsRepository.update { settings -> settings.copy(downloadOnWifiOnly = wifiOnly) }
    }

    /** The chat's system prompt and tools, which every chat's cache starts with. */
    private suspend fun chatWarmUpPrompt(): AssistantEngine.WarmUpPrompt {
        val settings = settingsRepository.settings.first()
        val systemPrompt = SystemPrompts.chat(settings.userName, LocalDate.now())
        val messages = listOf(ChatMessage.system(systemPrompt))
        return AssistantEngine.WarmUpPrompt(messages, toolExecutor.definitions)
    }

    private fun describeAnswerError(error: Throwable): String = when (error) {
        is EngineUnavailableException -> application.getString(R.string.error_model_unavailable)
        is PromptTooLongException, is ContextOverflowException -> application.getString(R.string.error_message_too_long)
        is NotEnoughMemoryException -> application.getString(R.string.error_out_of_memory)
        else -> {
            val detail = error.message ?: error.javaClass.simpleName
            application.getString(R.string.error_answer_failed, detail)
        }
    }

    private fun describeModelFailure(failure: ModelState.Failed): String = when (failure.reason) {
        ModelFailure.INSUFFICIENT_STORAGE -> {
            val required = Formatter.formatShortFileSize(application, failure.requiredBytes)
            application.getString(R.string.setup_failure_storage, required)
        }
        ModelFailure.NETWORK -> application.getString(R.string.setup_failure_network)
        ModelFailure.SERVER -> application.getString(R.string.setup_failure_server)
        ModelFailure.CORRUPT_DOWNLOAD -> application.getString(R.string.setup_failure_corrupt)
        ModelFailure.STORAGE -> application.getString(R.string.setup_failure_write)
        ModelFailure.INSTALL -> application.getString(R.string.setup_failure_install)
        ModelFailure.UNKNOWN -> application.getString(R.string.setup_failure_unknown)
    }

    /** The persisted "Hey Curie" switch, in the settings store. */
    private class SettingsWakeWordPreferences(private val repository: SettingsRepository) : WakeWordPreferences {
        override val enabled: Flow<Boolean> = repository.settings
            .map { settings -> settings.wakeWordEnabled }
            .distinctUntilChanged()

        override suspend fun setEnabled(enabled: Boolean) {
            repository.update { settings -> settings.copy(wakeWordEnabled = enabled) }
        }
    }

    private class RepositoryModelControls(private val repository: ModelRepository) : ModelControls {
        override val state: StateFlow<ModelState>
            get() = repository.state

        override fun startDownload() {
            repository.startDownload()
        }

        override fun cancelDownload() {
            repository.cancelDownload()
        }

        override suspend fun deleteModel(): Boolean = repository.deleteModel()
    }

    private companion object {
        const val MAX_VIDEO_DURATION_MS = 60_000L
        const val MAX_AUDIO_DURATION_MS = 2 * 60_000L
    }
}
