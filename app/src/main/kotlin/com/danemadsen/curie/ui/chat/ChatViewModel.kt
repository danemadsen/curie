package com.danemadsen.curie.ui.chat

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danemadsen.curie.R
import com.danemadsen.curie.audio.AudioRecorder
import com.danemadsen.curie.audio.ClipPlayback
import com.danemadsen.curie.audio.ClipPlaybackState
import com.danemadsen.curie.audio.MicrophoneArbiter
import com.danemadsen.curie.audio.MicrophoneSource
import com.danemadsen.curie.audio.MicrophoneUnavailableException
import com.danemadsen.curie.audio.RecordingPurpose
import com.danemadsen.curie.audio.SampleBuffer
import com.danemadsen.curie.audio.SpeechOutput
import com.danemadsen.curie.audio.WavFile
import com.danemadsen.curie.data.Attachment
import com.danemadsen.curie.data.AttachmentImportException
import com.danemadsen.curie.data.AttachmentKind
import com.danemadsen.curie.data.AttachmentLimits
import com.danemadsen.curie.data.AttachmentStore
import com.danemadsen.curie.data.Chat
import com.danemadsen.curie.data.ChatImportException
import com.danemadsen.curie.data.ChatRepository
import com.danemadsen.curie.data.ChatTransfer
import com.danemadsen.curie.data.Message
import com.danemadsen.curie.data.MessageRole as StoredRole
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.data.SpeakReplies
import com.danemadsen.curie.data.ThreadMessage
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.inference.ChatHistory
import com.danemadsen.curie.inference.ChatResponder
import com.danemadsen.curie.inference.ChatResponse
import com.danemadsen.curie.inference.ResponseOptions
import com.danemadsen.curie.inference.SpeakableText
import com.danemadsen.curie.inference.SpeechSink
import com.danemadsen.curie.inference.WebLookups
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Something the chat screen must do outside its state: tell the user, or ask for permissions. */
sealed interface ChatEvent {
    data class Notice(val text: String) : ChatEvent

    /** A tool needs these runtime permissions; asking now lets the next try succeed. */
    data class RequestPermissions(val permissions: List<String>) : ChatEvent
}

/** What the chat flow needs of the app. */
class ChatDependencies(
    val context: Application,
    val chats: ChatRepository,
    val transfer: ChatTransfer,
    val attachments: AttachmentStore,
    val attachmentLimits: AttachmentLimits,
    val settings: Flow<Settings>,
    val modelStatus: StateFlow<ModelStatus>,
    val engine: AssistantEngine,
    val responder: ChatResponder,
    val microphone: MicrophoneSource,
    val arbiter: MicrophoneArbiter,
    val createClipPlayer: (CoroutineScope) -> ClipPlayback,
    val createSpeechOutput: () -> SpeechOutput,
)

/**
 * The chat screen's state holder. It shows the selected chat's visible thread, sends the
 * composer's message (text, picked or captured media, a recorded voice message) and streams the
 * answer through the [ChatResponder], keeps alternatives of edited messages and regenerated
 * answers as siblings, plays clips, reads answers aloud (synthesising their speech once and
 * storing it with the message), and imports and exports chats.
 *
 * Voice messages and spoken replies: a message with recorded audio is answered in AUTO mode (the
 * model may speak) unless replies are never spoken; whether the reply plays follows
 * [Settings.speakReplies].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val dependencies: ChatDependencies,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val context = dependencies.context
    private val chats = dependencies.chats
    private val engine = dependencies.engine
    private val responder = dependencies.responder
    private val clips = dependencies.createClipPlayer(viewModelScope)
    private val speechOutput = dependencies.createSpeechOutput()
    private val eventChannel = Channel<ChatEvent>(Channel.BUFFERED)

    private val session = MutableStateFlow(ChatSession(activeChatId = savedState[KEY_ACTIVE_CHAT]))

    private var generationJob: Job? = null
    private var answerControl: ChatResponder.AnswerControl? = null
    private var speechJob: Job? = null

    /** Opens the apps a finished answer asked for once its reply has been spoken. */
    private var appLaunchJob: Job? = null

    private var recordingJob: Job? = null
    private var recording: SampleBuffer? = null

    /** The visible thread as last shown, to find the files of its attachments and replies. */
    @Volatile
    private var visibleThread: List<ThreadMessage> = emptyList()

    /** The message being typed; [ChatAction.Send] reads and clears it. */
    val prompt = TextFieldState()

    val events: Flow<ChatEvent> = eventChannel.receiveAsFlow()

    private val activeChatIds: Flow<String?> = session
        .map { current -> current.activeChatId }
        .distinctUntilChanged()

    private val threads: Flow<List<ThreadMessage>> = activeChatIds.flatMapLatest { chatId -> observeThread(chatId) }

    private val thread: Flow<List<ThreadMessage>> = threads.onEach { items -> visibleThread = items }

    private val chatList: Flow<List<ChatSummary>> = chats.observeChats().map { all ->
        all.map { chat -> ChatSummary(chat.id, chat.title) }
    }

    private val personas: Flow<Pair<Persona, Persona>> = dependencies.settings.map { settings ->
        val user = Persona(settings.userName, settings.userImage)
        val assistant = Persona(settings.assistantName, settings.assistantImage)
        user to assistant
    }

    private val localState: Flow<Pair<ChatSession, ClipPlaybackState?>> =
        combine(session, clips.state) { current, playback -> current to playback }

    private val uiStates: Flow<ChatUiState> = combine(
        dependencies.modelStatus,
        personas,
        chatList,
        thread,
        localState,
    ) { status, people, summaries, items, local ->
        toUiState(status, people, summaries, items, local.first, local.second)
    }

    private val initialUiState: ChatUiState = run {
        val defaultUser = Persona(Settings.DEFAULT_USER_NAME)
        val defaultAssistant = Persona(Settings.DEFAULT_ASSISTANT_NAME)
        ChatUiState(modelStatus = dependencies.modelStatus.value, user = defaultUser, assistant = defaultAssistant)
    }

    val uiState: StateFlow<ChatUiState> = uiStates
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialUiState)

    /** The chat currently shown; null for a new chat that has no message yet. */
    val activeChatId: String?
        get() = session.value.activeChatId

    // === Actions ===

    /** The actions that need no activity; the screen handles the others (pickers, navigation). */
    fun onAction(action: ChatAction) {
        when (action) {
            ChatAction.ClearChats -> clearChats()
            ChatAction.NewChat -> selectChat(null)
            is ChatAction.SelectChat -> selectChat(action.chatId)
            is ChatAction.RenameChat -> launchAction { chats.renameChat(action.chatId, action.title) }
            is ChatAction.DeleteChat -> deleteChat(action.chatId)
            is ChatAction.Speak -> speak(action.messageId)
            ChatAction.StopSpeaking -> stopSpeaking()
            is ChatAction.Regenerate -> regenerate(action.messageId)
            is ChatAction.StartEditing -> session.update { current -> current.copy(editingMessageId = action.messageId) }
            is ChatAction.ConfirmEdit -> confirmEdit(action.messageId, action.text)
            ChatAction.CancelEdit -> session.update { current -> current.copy(editingMessageId = null) }
            is ChatAction.ShowPreviousSibling -> launchAction { chats.selectSibling(action.messageId, -1) }
            is ChatAction.ShowNextSibling -> launchAction { chats.selectSibling(action.messageId, 1) }
            is ChatAction.DeleteMessage -> launchAction { chats.deleteMessage(action.messageId) }
            is ChatAction.ToggleAudio -> toggleAudio(action.clipId)
            is ChatAction.OpenToolLink -> openToolLink(action.messageId, action.toolIndex)
            ChatAction.Send -> send()
            ChatAction.StopGenerating -> answerControl?.stop()
            is ChatAction.RemoveAttachment -> removeAttachment(action.attachmentId)
            ChatAction.CancelRecording -> finishRecording(send = false)
            ChatAction.SendRecording -> finishRecording(send = true)
            ChatAction.OpenModelStatus,
            ChatAction.StartCall,
            ChatAction.ImportChats,
            is ChatAction.ExportChat,
            ChatAction.OpenSettings,
            is ChatAction.AddMedia,
            ChatAction.StartRecording,
            is ChatAction.OpenAttachment,
            -> Unit
        }
    }

    /**
     * The screen is shown: load the model if it can be, and have the chat's system prompt cached
     * (in the engine's own scope: it outlasts this screen, and a failure there is only logged).
     */
    fun onScreenVisible() {
        val status = dependencies.modelStatus.value
        val canLoad = status is ModelStatus.Loading || status is ModelStatus.Ready || status is ModelStatus.Failed
        if (canLoad) {
            engine.requestChatWarmUp()
        }
    }

    /** Media shared with Curie from another app: a new chat with it in the composer. */
    fun onSharedMedia(uris: List<Uri>, mimeType: String?) {
        selectChat(null)
        for (uri in uris) {
            addMedia(uri, mimeType)
        }
    }

    /** Imports a picked, captured or shared file into the composer. */
    fun addMedia(uri: Uri, mimeTypeHint: String? = null) {
        launchAction {
            val attachment = importAttachment(uri, mimeTypeHint) ?: return@launchAction
            session.update { current -> current.copy(pending = current.pending + attachment) }
        }
    }

    /**
     * A file for the camera to write a [kind] capture into ([onCaptureFinished] imports it); null
     * (with a notice) when storage has no room for it.
     */
    suspend fun createCaptureUri(kind: AttachmentKind): Uri? {
        val uri = try {
            dependencies.attachments.createCaptureUri(kind)
        } catch (error: IOException) {
            Log.w(TAG, "no room for a capture", error)
            notify(context.getString(R.string.attachment_storage_failed))
            return null
        }
        savedState[KEY_PENDING_CAPTURE] = uri
        return uri
    }

    fun onCaptureFinished(isSaved: Boolean) {
        val uri = savedState.get<Uri>(KEY_PENDING_CAPTURE) ?: return
        savedState.remove<Uri>(KEY_PENDING_CAPTURE)
        if (isSaved) {
            addMedia(uri)
        } else {
            launchAction { deleteCapture(uri) }
        }
    }

    /** Starts recording a voice message; the screen calls it once the microphone is allowed. */
    fun startRecording() {
        if (recordingJob != null) {
            return
        }
        if (!dependencies.arbiter.acquire(MicrophoneArbiter.Owner.VOICE_MESSAGE)) {
            notify(context.getString(R.string.recording_microphone_busy))
            return
        }
        stopSpeaking()

        val buffer = SampleBuffer()
        val maxSamples = maxRecordingSamples()
        recording = buffer
        session.update { current -> current.copy(recordingMs = 0) }
        recordingJob = viewModelScope.launch {
            try {
                val chunks = dependencies.microphone.record(RecordingPurpose.VOICE_MESSAGE)
                chunks.collect { chunk ->
                    buffer.append(chunk)
                    val elapsedMs = buffer.size * 1000L / AudioRecorder.SAMPLE_RATE
                    session.update { current -> current.copy(recordingMs = elapsedMs) }
                    if (buffer.size >= maxSamples) {
                        finishRecording(send = true)
                    }
                }
            } catch (error: MicrophoneUnavailableException) {
                Log.w(TAG, "recording failed", error)
                val text = if (error.isPermissionMissing) R.string.recording_microphone_needed else R.string.recording_failed
                notify(context.getString(text))
                finishRecording(send = false)
            }
        }
    }

    /** Reads the chat exports at [uris] into new chats and shows the last one. */
    fun importChats(uris: List<Uri>) {
        launchAction {
            var lastChat: Chat? = null
            for (uri in uris) {
                lastChat = importChat(uri) ?: lastChat
            }
            val chat = lastChat ?: return@launchAction
            selectChat(chat.id)
        }
    }

    /** Writes the chat's visible thread as JSON to [uri] (a document the user created). */
    fun exportChat(chatId: String, uri: Uri) {
        launchAction {
            val text = try {
                val result = withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                    output.use { stream -> dependencies.transfer.exportChat(chatId, stream) }
                }
                if (result.omittedAttachments > 0) {
                    val count = result.omittedAttachments
                    context.resources.getQuantityString(R.plurals.export_omitted_attachments, count, count)
                } else {
                    context.getString(R.string.export_done)
                }
            } catch (error: IOException) {
                Log.w(TAG, "export failed", error)
                context.getString(R.string.export_failed)
            } catch (error: SecurityException) {
                Log.w(TAG, "export failed", error)
                context.getString(R.string.export_failed)
            }
            notify(text)
        }
    }

    /** The suggested file name of a chat's export. */
    fun exportFileName(chatId: String): String {
        val title = uiState.value.chats.firstOrNull { chat -> chat.id == chatId }?.title
        val fallback = context.getString(R.string.drawer_untitled_chat)
        val name = if (title.isNullOrBlank()) fallback else title
        val safeName = name.replace(UNSAFE_FILE_CHARACTERS, "_")
        return context.getString(R.string.export_file_name, safeName)
    }

    /** A message's picture or video (or a composer attachment), for viewing it in another app. */
    fun attachmentFile(attachmentId: String): Attachment? {
        val stored = storedAttachment(attachmentId) ?: return null
        return if (stored.isAvailable) stored else null
    }

    /** True while a call can start: the model is ready and no answer is being generated. */
    fun canStartCall(): Boolean {
        val isReady = dependencies.modelStatus.value is ModelStatus.Ready
        return isReady && generationJob?.isActive != true
    }

    override fun onCleared() {
        stopSpeaking()
        clips.release()
        speechOutput.release()
        finishRecording(send = false)
    }

    // === Sending and answering ===

    private fun send() {
        val text = prompt.text.toString().trim()
        val pending = session.value.pending
        val canSend = text.isNotEmpty() || pending.isNotEmpty()
        if (!canSend || !isReadyToAnswer()) {
            return
        }

        prompt.clearText()
        session.update { current -> current.copy(pending = emptyList()) }
        sendMessage(text, pending)
    }

    private fun sendMessage(text: String, attachments: List<Attachment>) {
        startGeneration { control ->
            val chatId = ensureChat()
            val visible = chats.getThread(chatId)
            val parentId = visible.lastOrNull()?.message?.id
            val question = chats.appendMessage(chatId, parentId, StoredRole.USER, text, attachments)
            val isSpoken = ChatHistory.isSpoken(attachments)
            val answer = chats.appendMessage(chatId, question.id, StoredRole.ASSISTANT, "")
            answer(chatId, answer.id, isSpoken, control)
        }
    }

    private fun regenerate(messageId: String) {
        val chatId = session.value.activeChatId ?: return
        if (!isReadyToAnswer()) {
            return
        }
        startGeneration { control ->
            val visible = chats.getThread(chatId)
            val index = visible.indexOfFirst { item -> item.message.id == messageId }
            if (index < 0) {
                return@startGeneration
            }
            val earlier = visible.subList(0, index)
            val question = earlier.lastOrNull { item -> item.message.role == StoredRole.USER }
            val questionAttachments = question?.attachments.orEmpty()
            val isSpoken = ChatHistory.isSpoken(questionAttachments)
            val sibling = chats.addSibling(messageId, "")
            answer(chatId, sibling.id, isSpoken, control)
        }
    }

    private fun confirmEdit(messageId: String, text: String) {
        val chatId = session.value.activeChatId ?: return
        session.update { current -> current.copy(editingMessageId = null) }
        if (!isReadyToAnswer()) {
            return
        }
        startGeneration { control ->
            val visible = chats.getThread(chatId)
            val original = visible.firstOrNull { item -> item.message.id == messageId } ?: return@startGeneration
            val attachments = original.attachments
            val edited = chats.addSibling(messageId, text.trim(), attachments)
            val isSpoken = ChatHistory.isSpoken(attachments)
            val answer = chats.appendMessage(chatId, edited.id, StoredRole.ASSISTANT, "")
            answer(chatId, answer.id, isSpoken, control)
        }
    }

    /**
     * Runs [block] as the one generation of the screen, which the composer shows and can stop. Its
     * stop switch exists from the start, so a stop pressed before the answer is generated still
     * counts, and it goes away with the generation, so a later stop cannot reach anything else.
     */
    private fun startGeneration(block: suspend (ChatResponder.AnswerControl) -> Unit) {
        // A new answer supersedes apps the last one was about to open (before its speech stops)
        appLaunchJob?.cancel()
        appLaunchJob = null
        stopSpeaking()
        setGenerating(true)
        val control = responder.newAnswer()
        answerControl = control
        generationJob = viewModelScope.launch {
            try {
                block(control)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The answer's own failures are stored on it; this is the database or the files failing
                Log.e(TAG, "answering failed", error)
                val detail = error.message ?: error.javaClass.simpleName
                notify(context.getString(R.string.error_answer_failed, detail))
            } finally {
                if (answerControl === control) {
                    answerControl = null
                }
                setGenerating(false)
            }
        }
    }

    /** Generates into [messageId] until done or stopped, then stores and plays its speech as the settings say. */
    private suspend fun answer(
        chatId: String,
        messageId: String,
        isSpoken: Boolean,
        control: ChatResponder.AnswerControl,
    ) {
        val settings = dependencies.settings.first()
        val wantsSpeech = when (settings.speakReplies) {
            SpeakReplies.NEVER -> false
            SpeakReplies.VOICE_MESSAGES -> isSpoken
            SpeakReplies.ALWAYS -> true
        }

        // A reply to be heard is spoken by the model itself, interleaved with its text; its speech is
        // stored and plays once the answer is complete
        val mode = if (wantsSpeech) GenerationMode.SPOKEN else GenerationMode.TEXT
        val options = ResponseOptions(mode = mode, userName = settings.userName)

        val response = responder.respond(chatId, messageId, options, control)
        if (response.permissionsNeeded.isNotEmpty()) {
            val permissions = response.permissionsNeeded.toList()
            eventChannel.trySend(ChatEvent.RequestPermissions(permissions))
        }
        if (!response.isComplete) {
            return
        }

        handleSpeech(response, wantsSpeech)

        if (response.appLaunches.isNotEmpty()) {
            appLaunchJob = viewModelScope.launch {
                awaitSpeechEnd(response.finalMessageId)
                openApps(response)
            }
        }
    }

    /** Waits while the reply [messageId] is read or played aloud, so an app it opens does not cut it off. */
    private suspend fun awaitSpeechEnd(messageId: String) {
        speechJob?.join()

        // A clip reports "not playing" while it prepares: only a pause after it has played counts
        val clipId = replyClipId(messageId)
        var hasPlayed = false
        clips.state.first { clip ->
            if (clip == null || clip.clipId != clipId) {
                return@first true
            }
            if (clip.isPlaying) {
                hasPlayed = true
            }
            hasPlayed && !clip.isPlaying
        }
    }

    private fun openToolLink(messageId: String, toolIndex: Int) {
        launchAction {
            val message = chats.getMessage(messageId) ?: return@launchAction
            val record = message.toolCalls.getOrNull(toolIndex) ?: return@launchAction
            val isOpen = responder.openLink(record)
            if (!isOpen) {
                notify(context.getString(R.string.tool_link_failed))
            }
        }
    }

    private fun openApps(response: ChatResponse) {
        val isEveryAppOpen = responder.openApps(response)
        if (!isEveryAppOpen) {
            notify(context.getString(R.string.apps_blocked_in_background))
        }
    }

    private suspend fun handleSpeech(response: ChatResponse, wantsSpeech: Boolean) {
        val messageId = response.finalMessageId
        if (response.speech.isNotEmpty()) {
            val file = storeSpeech(messageId, response.speech) ?: return
            if (wantsSpeech) {
                playClip(replyClipId(messageId), file)
            }
            return
        }
        if (wantsSpeech && response.finalText.isNotBlank()) {
            readAloud(messageId, response.finalText)
        }
    }

    private suspend fun ensureChat(): String {
        val existing = session.value.activeChatId
        if (existing != null) {
            return existing
        }
        val chat = chats.createChat()
        setActiveChat(chat.id)
        return chat.id
    }

    private fun isReadyToAnswer(): Boolean {
        val isReady = dependencies.modelStatus.value is ModelStatus.Ready
        val isBusy = generationJob?.isActive == true
        return isReady && !isBusy
    }

    private fun setGenerating(isGenerating: Boolean) {
        session.update { current -> current.copy(isGenerating = isGenerating) }
    }

    // === Speech ===

    private fun speak(messageId: String) {
        launchAction {
            val finalRoundId = finalRoundOf(messageId)
            val message = chats.getMessage(finalRoundId) ?: return@launchAction
            val speechPath = message.speechFile
            if (speechPath != null) {
                stopSpeaking()
                playClip(replyClipId(finalRoundId), File(speechPath))
            } else if (message.text.isNotBlank()) {
                readAloud(finalRoundId, message.text)
            }
        }
    }

    /**
     * The last round of the answer [messageId] starts: an answer that used tools is a message per
     * round, with the tools' results between them, and its final text and speech are in the last.
     */
    private suspend fun finalRoundOf(messageId: String): String {
        val chatId = session.value.activeChatId ?: return messageId
        val thread = chats.getThread(chatId)
        val start = thread.indexOfFirst { item -> item.message.id == messageId }
        if (start < 0) {
            return messageId
        }

        var finalRoundId = messageId
        var index = start + 1
        while (index + 1 < thread.size) {
            val isToolResult = thread[index].message.role == StoredRole.TOOL
            val isNextRound = thread[index + 1].message.role == StoredRole.ASSISTANT
            if (!isToolResult || !isNextRound) {
                break
            }
            finalRoundId = thread[index + 1].message.id
            index += 2
        }
        return finalRoundId
    }

    /** Plays a voice message or a stored reply, unless another app keeps the audio (then says so). */
    private fun playClip(clipId: String, file: File) {
        val isPlaying = clips.play(clipId, file)
        if (!isPlaying) {
            notify(context.getString(R.string.audio_busy))
        }
    }

    /**
     * Synthesises [text] with the speech conversation while playing it, then stores the speech.
     * Playback that ends early (the stop button, or another app taking the audio) ends it all.
     */
    private fun readAloud(messageId: String, text: String) {
        stopSpeaking()
        speechJob = viewModelScope.launch {
            val isStarted = speechOutput.begin()
            if (!isStarted) {
                notify(context.getString(R.string.audio_busy))
                return@launch
            }

            val expectedSeconds = SpeakableText.estimatedSeconds(text)
            speechOutput.expectDuration(expectedSeconds)
            session.update { current -> current.copy(readingMessageId = messageId) }
            try {
                coroutineScope {
                    val synthesis = launch { synthesizeAndStore(messageId, text) }
                    val hasPlayed = speechOutput.awaitFinished()
                    if (!hasPlayed) {
                        synthesis.cancel()
                    }
                }
            } catch (error: CancellationException) {
                speechOutput.stop()
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "reading aloud failed", error)
                speechOutput.stop()
                notify(context.getString(R.string.speak_failed))
            } finally {
                session.update { current -> current.copy(readingMessageId = null) }
            }
        }
    }

    /** Streams the speech of [text] to the output as it is made; once all of it was made, stores it. */
    private suspend fun synthesizeAndStore(messageId: String, text: String) {
        val samples = SampleBuffer(initialCapacity = 0)
        val sink = SpeechSink { chunk ->
            synchronized(samples) { samples.append(chunk) }
            speechOutput.write(chunk)
        }
        val isComplete = engine.synthesize(text, sink)
        speechOutput.endOfAnswer()
        if (isComplete) {
            val speech = synchronized(samples) { samples.toArray() }
            storeSpeech(messageId, speech)
        }
    }

    private fun stopSpeaking() {
        val job = speechJob
        speechJob = null
        job?.cancel()
        speechOutput.stop()
        clips.stop()
    }

    /** Writes [samples] as the message's WAV and stores its path; null when that failed. */
    private suspend fun storeSpeech(messageId: String, samples: FloatArray): File? {
        if (samples.isEmpty()) {
            return null
        }
        val file = chats.speechFileFor(messageId)
        return try {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                WavFile.write(file, samples, Lfm3Omni.OUTPUT_SAMPLE_RATE)
            }
            chats.updateMessage(messageId, speechFile = file.path)
            file
        } catch (error: IOException) {
            Log.w(TAG, "storing the speech of $messageId failed", error)
            null
        }
    }

    private fun toggleAudio(clipId: String) {
        val file = clipFile(clipId) ?: return
        stopSpeakingStream()
        val isAllowed = clips.toggle(clipId, file)
        if (!isAllowed) {
            notify(context.getString(R.string.audio_busy))
        }
    }

    /** Stops a synthesis being streamed, leaving a clip that plays alone. */
    private fun stopSpeakingStream() {
        val job = speechJob
        speechJob = null
        job?.cancel()
        speechOutput.stop()
    }

    private fun clipFile(clipId: String): File? {
        val messageId = messageIdOfReplyClip(clipId)
        if (messageId != null) {
            val item = visibleThread.firstOrNull { threadMessage -> threadMessage.message.id == messageId } ?: return null
            val path = item.message.speechFile ?: return null
            return File(path)
        }
        val stored = storedAttachment(clipId) ?: return null
        return File(stored.path)
    }

    // === Recording ===

    private fun finishRecording(send: Boolean) {
        val job = recordingJob ?: return
        val buffer = recording
        recordingJob = null
        recording = null
        job.cancel()
        dependencies.arbiter.release(MicrophoneArbiter.Owner.VOICE_MESSAGE)
        session.update { current -> current.copy(recordingMs = null) }
        if (!send || buffer == null) {
            return
        }

        // A chunk past the limit would make the import refuse the clip
        val samples = buffer.toArray(maxRecordingSamples())
        if (samples.size < MIN_RECORDING_SAMPLES) {
            notify(context.getString(R.string.recording_too_short))
            return
        }
        launchAction {
            val voiceMessage = storeRecording(samples) ?: return@launchAction
            val text = prompt.text.toString().trim()
            val pending = session.value.pending
            prompt.clearText()
            session.update { current -> current.copy(pending = emptyList()) }
            if (isReadyToAnswer()) {
                sendMessage(text, pending + voiceMessage)
            } else {
                // The model went away while recording: keep everything in the composer
                session.update { current -> current.copy(pending = pending + voiceMessage) }
            }
        }
    }

    /** The recording as a 16 kHz WAV attachment, through a capture file of the attachment store. */
    private suspend fun storeRecording(samples: FloatArray): Attachment? {
        val uri = createCaptureUri(AttachmentKind.AUDIO) ?: return null
        savedState.remove<Uri>(KEY_PENDING_CAPTURE)
        try {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                output.use { stream -> WavFile.write(stream, samples, AudioRecorder.SAMPLE_RATE) }
            }
        } catch (error: IOException) {
            Log.w(TAG, "saving the voice message failed", error)
            notify(context.getString(R.string.attachment_storage_failed))
            deleteCapture(uri)
            return null
        }
        return importAttachment(uri, WAV_MIME_TYPE)
    }

    /** Just under the longest audio the attachment store takes. */
    private fun maxRecordingSamples(): Int {
        val maxMs = dependencies.attachmentLimits.maxAudioDurationMs - RECORDING_LIMIT_MARGIN_MS
        return (maxMs * AudioRecorder.SAMPLE_RATE / 1000).toInt()
    }

    // === Attachments ===

    private suspend fun importAttachment(uri: Uri, mimeTypeHint: String?): Attachment? {
        return try {
            dependencies.attachments.import(uri, mimeTypeHint)
        } catch (error: AttachmentImportException) {
            Log.i(TAG, "attachment refused: ${error.message}")
            notify(describeImportFailure(error))
            null
        }
    }

    private fun removeAttachment(attachmentId: String) {
        val attachment = session.value.pending.firstOrNull { item -> item.id == attachmentId } ?: return
        session.update { current -> current.copy(pending = current.pending - attachment) }
        launchAction { chats.discardAttachment(attachment) }
    }

    private suspend fun deleteCapture(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (error: RuntimeException) {
                Log.d(TAG, "the capture $uri is gone already", error)
            }
        }
    }

    /** An attachment of the visible thread or of the composer. */
    private fun storedAttachment(attachmentId: String): Attachment? {
        val pending = session.value.pending.firstOrNull { attachment -> attachment.id == attachmentId }
        if (pending != null) {
            return pending
        }
        return visibleThread.firstNotNullOfOrNull { item ->
            item.attachments.firstOrNull { attachment -> attachment.id == attachmentId }
        }
    }

    private fun describeImportFailure(error: AttachmentImportException): String = when (error) {
        is AttachmentImportException.Unsupported -> context.getString(R.string.attachment_unsupported)
        is AttachmentImportException.TooLarge -> {
            val limit = Formatter.formatShortFileSize(context, error.limitBytes)
            context.getString(R.string.attachment_too_large, limit)
        }
        is AttachmentImportException.TooLong -> {
            val limit = formatMinutes(error.limitMs)
            val text = when (error.kind) {
                AttachmentKind.VIDEO -> R.string.attachment_video_too_long
                else -> R.string.attachment_audio_too_long
            }
            context.getString(text, limit)
        }
        is AttachmentImportException.Unreadable -> context.getString(R.string.attachment_unreadable)
        is AttachmentImportException.StorageFailed -> context.getString(R.string.attachment_storage_failed)
    }

    private fun formatMinutes(milliseconds: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds).toInt()
        return context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
    }

    // === Chats ===

    private fun selectChat(chatId: String?) {
        if (chatId == session.value.activeChatId) {
            return
        }
        stopSpeaking()
        session.update { current -> current.copy(editingMessageId = null) }
        setActiveChat(chatId)
    }

    private fun setActiveChat(chatId: String?) {
        savedState[KEY_ACTIVE_CHAT] = chatId
        session.update { current -> current.copy(activeChatId = chatId) }
    }

    private fun deleteChat(chatId: String) {
        if (chatId == session.value.activeChatId) {
            answerControl?.stop()
            selectChat(null)
        }
        launchAction { chats.deleteChat(chatId) }
    }

    private fun clearChats() {
        answerControl?.stop()
        selectChat(null)
        launchAction { chats.deleteAllChats() }
    }

    private suspend fun importChat(uri: Uri): Chat? {
        return try {
            val result = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                input.use { stream -> dependencies.transfer.importChat(stream) }
            }
            notify(context.getString(R.string.import_done, result.chat.title))
            if (result.skippedAttachments > 0) {
                val count = result.skippedAttachments
                notify(context.resources.getQuantityString(R.plurals.import_skipped_attachments, count, count))
            }
            if (result.skippedToolCalls > 0) {
                val count = result.skippedToolCalls
                notify(context.resources.getQuantityString(R.plurals.import_skipped_tool_calls, count, count))
            }
            result.chat
        } catch (error: ChatImportException) {
            Log.i(TAG, "import refused: ${error.message}")
            notify(describeChatImportFailure(error))
            null
        } catch (error: IOException) {
            Log.w(TAG, "import failed", error)
            notify(context.getString(R.string.import_unreadable))
            null
        } catch (error: SecurityException) {
            Log.w(TAG, "import failed", error)
            notify(context.getString(R.string.import_unreadable))
            null
        }
    }

    private fun describeChatImportFailure(error: ChatImportException): String = when (error) {
        is ChatImportException.Malformed -> context.getString(R.string.import_malformed)
        is ChatImportException.Unsupported -> context.getString(R.string.import_unsupported)
        is ChatImportException.TooLarge -> context.getString(R.string.import_too_large)
        is ChatImportException.Unreadable -> context.getString(R.string.import_unreadable)
        is ChatImportException.StorageFailed -> context.getString(R.string.import_storage_failed)
    }

    // === State ===

    private fun toUiState(
        status: ModelStatus,
        people: Pair<Persona, Persona>,
        summaries: List<ChatSummary>,
        items: List<ThreadMessage>,
        local: ChatSession,
        playback: ClipPlaybackState?,
    ): ChatUiState {
        val messages = ArrayList<MessageUi>(items.size)
        var previousRole: StoredRole? = null
        for (item in items) {
            val continuesAnswer = previousRole == StoredRole.TOOL
            val messageUi = toMessageUi(item, continuesAnswer)
            if (messageUi != null) {
                messages.add(messageUi)
            }
            previousRole = item.message.role
        }
        val pending = local.pending.map { attachment -> toAttachmentUi(attachment) }
        val recordingMs = local.recordingMs
        val composer = ComposerUiState(
            attachments = pending,
            recording = recordingMs?.let { elapsed -> RecordingUi(elapsed) },
        )
        val audioPlayback = playback?.let { state ->
            AudioPlayback(clipId = state.clipId, isPlaying = state.isPlaying, positionMillis = state.positionMs)
        }
        val isPlayingClip = playback != null && playback.isPlaying
        val playingReply = if (isPlayingClip) messageIdOfReplyClip(playback.clipId) else null

        // An answer's speech belongs to its last round; its controls are on its first
        val speakingRound = local.readingMessageId ?: playingReply
        val speakingAnswer = speakingRound?.let { roundId -> answerStartOf(roundId, messages) }

        return ChatUiState(
            modelStatus = status,
            user = people.first,
            assistant = people.second,
            chats = summaries,
            activeChatId = local.activeChatId,
            messages = messages,
            isGenerating = local.isGenerating,
            editingMessageId = local.editingMessageId,
            speakingMessageId = speakingAnswer,
            audioPlayback = audioPlayback,
            composer = composer,
        )
    }

    /** The first message of the answer whose round [messageId] is (itself when it is not a continuation). */
    private fun answerStartOf(messageId: String, messages: List<MessageUi>): String {
        var index = messages.indexOfFirst { message -> message.id == messageId }
        if (index < 0) {
            return messageId
        }
        while (index > 0 && messages[index].isContinuation) {
            index--
        }
        return messages[index].id
    }

    private fun toMessageUi(item: ThreadMessage, continuesAnswer: Boolean): MessageUi? {
        val message = item.message
        val role = when (message.role) {
            StoredRole.USER -> MessageRole.USER
            StoredRole.ASSISTANT -> MessageRole.ASSISTANT
            StoredRole.TOOL -> return null
        }
        val attachments = item.attachments.map { attachment -> toAttachmentUi(attachment) }
        val toolActions = message.toolCalls.mapIndexed { index, record -> toToolAction(message, index, record) }
        return MessageUi(
            id = message.id,
            role = role,
            text = message.text,
            attachments = attachments,
            toolActions = toolActions,
            spokenReply = spokenReplyOf(message),
            siblingIndex = item.siblingIndex,
            siblingCount = item.siblingCount,
            error = message.error,
            isContinuation = continuesAnswer && role == MessageRole.ASSISTANT,
        )
    }

    private fun toAttachmentUi(attachment: Attachment): AttachmentUi {
        // A file that is gone (a restore without media) shows as an empty frame
        val uri = if (attachment.isAvailable) attachment.path else ""
        return when (attachment.kind) {
            AttachmentKind.IMAGE -> AttachmentUi.Image(attachment.id, uri, attachment.width, attachment.height)
            AttachmentKind.VIDEO -> AttachmentUi.Video(
                id = attachment.id,
                uri = uri,
                durationMillis = attachment.durationMs,
                width = attachment.width,
                height = attachment.height,
            )
            AttachmentKind.AUDIO -> AttachmentUi.Audio(AudioClipUi(attachment.id, attachment.durationMs))
        }
    }

    private fun spokenReplyOf(message: Message): AudioClipUi? {
        val path = message.speechFile ?: return null
        val file = File(path)
        val pcmBytes = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0)
        val durationMs = pcmBytes / 2 * 1000 / Lfm3Omni.OUTPUT_SAMPLE_RATE
        return AudioClipUi(id = replyClipId(message.id), durationMillis = durationMs)
    }

    private fun toToolAction(message: Message, index: Int, record: ToolCallRecord): ToolActionUi {
        val succeeded = record.resultJson != null && !hasError(record.resultJson)
        return ToolActionUi(
            id = "${message.id}-tool-$index",
            icon = toolIconOf(record.name),
            summary = record.summary ?: record.name,
            succeeded = succeeded,
            isLink = WebLookups.isHeldBack(record),
        )
    }

    private fun hasError(resultJson: String): Boolean {
        val result = try {
            Json.parseToJsonElement(resultJson)
        } catch (error: SerializationException) {
            return false
        }
        return result is JsonObject && result.containsKey("error")
    }

    private fun notify(text: String) {
        eventChannel.trySend(ChatEvent.Notice(text))
    }

    /**
     * Runs a user's action in the screen's scope. A failure it does not handle itself (the
     * database failing, say) is logged and shown instead of crashing the app.
     */
    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "a chat action failed", error)
                notify(context.getString(R.string.chat_action_failed))
            }
        }
    }

    /** The thread of the chat [chatId]; nothing for a new chat. */
    private fun observeThread(chatId: String?): Flow<List<ThreadMessage>> {
        if (chatId == null) {
            return flowOf(emptyList())
        }
        return chats.observeThread(chatId)
    }

    /** What the screen shows beyond the stored chats. */
    private data class ChatSession(
        val activeChatId: String? = null,
        val pending: List<Attachment> = emptyList(),
        val recordingMs: Long? = null,
        val editingMessageId: String? = null,
        val isGenerating: Boolean = false,
        val readingMessageId: String? = null,
    )

    companion object {
        private const val TAG = "ChatViewModel"
        private const val KEY_ACTIVE_CHAT = "activeChatId"
        private const val KEY_PENDING_CAPTURE = "pendingCapture"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val WAV_MIME_TYPE = "audio/wav"
        private const val WAV_HEADER_BYTES = 44L
        private const val REPLY_CLIP_PREFIX = "reply:"
        private const val RECORDING_LIMIT_MARGIN_MS = 100L

        /** A voice message shorter than this is a slip of the finger. */
        private const val MIN_RECORDING_SAMPLES = AudioRecorder.SAMPLE_RATE / 2

        private val UNSAFE_FILE_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun replyClipId(messageId: String): String = REPLY_CLIP_PREFIX + messageId

        fun messageIdOfReplyClip(clipId: String): String? =
            if (clipId.startsWith(REPLY_CLIP_PREFIX)) clipId.removePrefix(REPLY_CLIP_PREFIX) else null

        /** The tabler icon of a tool's actions in the chat. */
        fun toolIconOf(toolName: String): ToolIcon = when (toolName) {
            "play_music", "media_control" -> ToolIcon.MUSIC
            "play_video" -> ToolIcon.APP
            "navigate", "show_on_map" -> ToolIcon.MAP
            "call_contact" -> ToolIcon.CALL
            "send_text_message", "send_email" -> ToolIcon.MESSAGE
            "set_alarm" -> ToolIcon.ALARM
            "set_timer" -> ToolIcon.TIMER
            "create_calendar_event" -> ToolIcon.CALENDAR
            "web_search" -> ToolIcon.SEARCH
            "open_url" -> ToolIcon.WEB
            "open_app" -> ToolIcon.APP
            "set_flashlight" -> ToolIcon.FLASHLIGHT
            "set_volume", "open_settings", "get_device_status" -> ToolIcon.SETTINGS
            else -> ToolIcon.OTHER
        }
    }
}
