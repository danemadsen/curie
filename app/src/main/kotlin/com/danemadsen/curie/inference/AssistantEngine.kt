package com.danemadsen.curie.inference

import android.content.ComponentCallbacks2
import android.util.Log
import com.danemadsen.curie.engine.Conversation
import com.danemadsen.curie.engine.OmniEngine
import com.danemadsen.curie.engine.SpeechDecoder
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.prompt.Prompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where the engine stands. */
sealed interface EngineState {
    /** Not in memory (yet, or again after being idle). Anything that generates loads it. */
    data object Unloaded : EngineState

    data object Loading : EngineState

    data object Ready : EngineState

    /** Loading failed; [message] says why. Asking again retries. */
    data class Failed(val message: String) : EngineState
}

/** The two caches the engine keeps: chats and calls take turns on [MAIN]; [SPEECH] reads answers aloud. */
enum class ConversationSlot(val capacity: Int) {
    MAIN(4096),
    SPEECH(2048),
}

/** Receives the 24 kHz samples of decoded speech, on the speech decoding thread. */
fun interface SpeechSink {
    fun onSamples(samples: FloatArray)
}

/** What the engine needs of the model's files. */
interface ModelSource {
    val modelDirectory: File

    /** True when the files are complete and installed, so the model can load. */
    suspend fun ensureReady(): Boolean
}

/** The model cannot be used now: not downloaded, or loading failed. */
class EngineUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An answer needed more memory than there was (decoding a long clip, say); the model stays loaded. */
class NotEnoughMemoryException(cause: OutOfMemoryError) : Exception("not enough memory for this answer", cause)

/**
 * Tells one caller's generations apart from everyone else's: [AssistantEngine.cancel] with an
 * owner stops only a generation started with it, so a chat's stop button never stops a call.
 */
class GenerationOwner

/**
 * The one on-device model, shared by chats, calls and reading answers aloud. It owns the
 * [OmniEngine]'s lifecycle and runs every generation on one dedicated thread, one at a time;
 * speech frames are decoded on a second thread while the answer is generated.
 *
 * It loads when something needs it ([requestLoad], or any generation) once the model's files are
 * ready, then warms the main conversation with the chat's system prompt ([requestChatWarmUp]) so
 * the first message does not wait for it. It unloads after [idleUnloadDelayMs] unused while the
 * app is in the background, when the system runs critically low on memory, and on [unload]; a
 * [hold] (a call in progress) keeps it loaded, and keeps the chat's warm-up out of the call's cache.
 *
 * [scope] must be supervised (a SupervisorJob): a failed load must not cancel it.
 */
class AssistantEngine(
    private val model: ModelSource,
    private val engineFactory: EngineFactory,
    private val rendererFactory: PromptRendererFactory,
    private val scope: CoroutineScope,
    private val idleUnloadDelayMs: Long = IDLE_UNLOAD_DELAY_MS,
    private val engineDispatcher: CoroutineDispatcher = newThreadDispatcher(ENGINE_THREAD_NAME),
    private val speechDispatcher: CoroutineDispatcher = newThreadDispatcher(SPEECH_THREAD_NAME),
) {
    /** The chat's system prompt and tools, which every chat's cache starts with; null skips warming up. */
    var chatWarmUpPrompt: (suspend () -> WarmUpPrompt?)? = null

    private val mutableState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    private val runMutex = Mutex()
    private val holds = AtomicInteger()
    private val loadLock = Any()
    private val runningLock = Any()

    @Volatile
    private var loaded: LoadedModel? = null

    /** The generation or warm-up on the engine thread now; guarded by [runningLock]. */
    private var running: RunningWork? = null

    private var loading: Deferred<LoadedModel>? = null
    private var idleJob: Job? = null

    @Volatile
    private var isAppVisible = true

    /** The model was freed while the app was hidden: showing the app again loads it back. */
    @Volatile
    private var wasUnloadedWhileHidden = false

    val state: StateFlow<EngineState> = mutableState.asStateFlow()

    /** Starts loading in the background when the model is not loaded yet. */
    fun requestLoad() {
        if (loaded != null) {
            return
        }
        val pending = startLoading()
        scope.launch {
            try {
                pending.await()
            } catch (error: EngineUnavailableException) {
                Log.i(TAG, "the model is not available: ${error.message}")
            }
        }
    }

    /**
     * Loads the model now (or waits for the load under way).
     *
     * @throws EngineUnavailableException when the model is not downloaded or fails to load
     */
    suspend fun load() {
        awaitLoaded()
    }

    /**
     * Generates an answer to [messages] (system prompt first) in the [slot] conversation, leaving
     * out the oldest turns when they do not fit with [GenerationRequest.maxNewSlots]. With
     * [speech], the answer's speech frames are decoded while it is generated and their samples
     * passed on; the call returns once the last samples were.
     *
     * [window] remembers where a growing conversation was cut (see [ContextFitter]); pass the
     * conversation's own, or null for a one-off prompt.
     *
     * [cancel] ends it early with [FinishReason.CANCELLED] and what was generated so far ([owner]
     * lets its caller stop it alone); cancelling the coroutine stops it too.
     *
     * @throws EngineUnavailableException when the model cannot be loaded
     * @throws PromptTooLongException when the last turn alone is too long
     * @throws NotEnoughMemoryException when the answer's media do not fit in memory
     */
    suspend fun generate(
        slot: ConversationSlot,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        request: GenerationRequest,
        listener: GenerationListener,
        speech: SpeechSink? = null,
        window: ContextWindow? = null,
        owner: GenerationOwner? = null,
    ): GenerationResult {
        return withModel { loadedModel ->
            val conversation = conversationOf(loadedModel, slot)

            // The marker a speaking mode opens its answer with takes a position of its own
            val markerSlots = if (request.mode in MARKED_MODES) 1 else 0
            val fitted = withContext(engineDispatcher) {
                ContextFitter.fit(
                    messages = messages,
                    tools = tools,
                    capacity = conversation.capacity,
                    wantedNewSlots = request.maxNewSlots + markerSlots,
                    renderer = loadedModel.renderer,
                    window = window,
                )
            }
            if (fitted.droppedMessages > 0) {
                Log.i(TAG, "left ${fitted.droppedMessages} old messages out of the ${slot.name} prompt")
            }
            val fittedRequest = request.copy(maxNewSlots = fitted.maxNewSlots - markerSlots)

            // While it runs (and when it fails) the cache is somewhere between two prompts
            loadedModel.lastPrompts.remove(slot)
            val result = try {
                generateFitted(loadedModel, conversation, fitted, fittedRequest, listener, speech, owner)
            } catch (error: OutOfMemoryError) {
                throw outOfMemory(conversation, error)
            }
            if (holdsWholePrompt(result)) {
                loadedModel.lastPrompts[slot] = fitted.prompt
            }
            result
        }
    }

    /**
     * Reads [text] (markdown) aloud with the [ConversationSlot.SPEECH] conversation, piece by
     * piece, passing the samples to [sink]. Returns false when [cancel] stopped it.
     */
    suspend fun synthesize(text: String, sink: SpeechSink): Boolean {
        val chunks = SpeakableText.chunks(text)
        for (chunk in chunks) {
            val messages = listOf(
                ChatMessage.system(SystemPrompts.TEXT_TO_SPEECH),
                ChatMessage.user(chunk),
            )
            val wantedFrames = chunk.length * TTS_FRAMES_PER_CHAR + TTS_EXTRA_FRAMES
            val request = GenerationRequest(mode = GenerationMode.SPEECH, maxNewSlots = wantedFrames)
            val result = generate(ConversationSlot.SPEECH, messages, emptyList(), request, SILENT_LISTENER, sink)
            if (result.finishReason == FinishReason.CANCELLED) {
                return false
            }
        }
        return true
    }

    /**
     * Brings the [slot] conversation's cache to [prompt] (a system prompt and tools) without
     * generating, so the next generation only feeds what follows it. Nothing happens when the last
     * prompt the conversation was given already starts with it: prefilling would rewind the cache.
     */
    suspend fun warmUp(slot: ConversationSlot, prompt: WarmUpPrompt) {
        warmUp(slot, prompt, skipWhileHeld = false)
    }

    /**
     * Starts caching the chat's system prompt ([chatWarmUpPrompt]) in the main conversation, so the
     * next chat message does not wait for it; it loads the model first if needed. Nothing happens
     * while a [hold] is held: the main conversation holds a call's turns then. A failure is only
     * logged, since warming up merely saves time.
     */
    fun requestChatWarmUp() {
        scope.launch { warmUpChat() }
    }

    /**
     * Stops the running generation or warm-up, whoever started it, at its next step (a call's
     * barge-in); nothing happens when none runs.
     */
    fun cancel() {
        synchronized(runningLock) {
            val work = running ?: return
            work.conversation.cancel()
        }
    }

    /** Stops the running generation if [owner] started it; anything else runs on. */
    fun cancel(owner: GenerationOwner) {
        synchronized(runningLock) {
            val work = running ?: return
            if (work.owner === owner) {
                work.conversation.cancel()
            }
        }
    }

    /** Keeps the model loaded until the returned handle is closed (a call in progress). */
    fun hold(): AutoCloseable {
        holds.incrementAndGet()
        cancelIdleUnload()
        val isClosed = AtomicBoolean(false)
        return AutoCloseable {
            val wasOpen = isClosed.compareAndSet(false, true)
            if (wasOpen) {
                holds.decrementAndGet()
                scheduleIdleUnloadIfHidden()
            }
        }
    }

    /** The app's UI was shown or hidden: the idle unload counts only while it is hidden. */
    fun onAppVisibilityChanged(visible: Boolean) {
        isAppVisible = visible
        if (!visible) {
            scheduleIdleUnloadIfHidden()
            return
        }
        cancelIdleUnload()
        if (wasUnloadedWhileHidden) {
            wasUnloadedWhileHidden = false
            requestLoad()
        }
    }

    /**
     * ComponentCallbacks2.onTrimMemory: critically low memory frees the model unless a call holds
     * it, but not while Curie is on screen (the system frees background apps first, and the user
     * is about to need the model).
     */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val isRunningCritical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        val isLastInLine = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        val isCritical = isLastInLine || (isRunningCritical && !isAppVisible)
        if (isCritical && holds.get() == 0) {
            scope.launch { unload() }
        }
    }

    /**
     * Frees the model after the running generation and any load under way (before deleting its
     * files, for one). Once it has begun, cancelling the caller does not stop it halfway.
     */
    suspend fun unload() {
        val pendingLoad = synchronized(loadLock) { loading }
        pendingLoad?.join()

        runMutex.lock()
        try {
            val loadedModel = loaded ?: return
            withContext(NonCancellable) {
                release(loadedModel)
                wasUnloadedWhileHidden = !isAppVisible
                mutableState.value = EngineState.Unloaded
            }
        } finally {
            runMutex.unlock()
        }
    }

    /** The loaded model, loading it first if needed; callers share one load. */
    private suspend fun awaitLoaded(): LoadedModel {
        val current = loaded
        if (current != null) {
            return current
        }
        val pending = startLoading()
        return pending.await()
    }

    /**
     * The load under way, or a new one. It runs in the app's scope: a caller that stops waiting
     * must not leak a half-made engine. It is registered at once, so [unload] can wait for it.
     */
    private fun startLoading(): Deferred<LoadedModel> = synchronized(loadLock) {
        val existing = loading
        if (existing != null && existing.isActive) {
            return existing
        }
        val started = scope.async { loadNow() }
        loading = started
        started
    }

    private suspend fun loadNow(): LoadedModel {
        val current = loaded
        if (current != null) {
            return current
        }

        mutableState.value = EngineState.Loading
        val isModelReady = try {
            model.ensureReady()
        } catch (error: CancellationException) {
            mutableState.value = EngineState.Unloaded
            throw error
        }
        if (!isModelReady) {
            mutableState.value = EngineState.Unloaded
            throw EngineUnavailableException("the model is not downloaded")
        }

        val loadedModel = try {
            withContext(engineDispatcher) { openModel(model.modelDirectory) }
        } catch (error: CancellationException) {
            mutableState.value = EngineState.Unloaded
            throw error
        } catch (error: Exception) {
            fail(error)
        } catch (error: OutOfMemoryError) {
            fail(error)
        }

        loaded = loadedModel
        mutableState.value = EngineState.Ready
        scheduleIdleUnloadIfHidden()
        requestChatWarmUp()
        return loadedModel
    }

    private fun fail(error: Throwable): Nothing {
        Log.e(TAG, "loading the model failed", error)
        val message = error.message ?: error.javaClass.simpleName
        mutableState.value = EngineState.Failed(message)
        throw EngineUnavailableException(message, error)
    }

    private fun openModel(directory: File): LoadedModel {
        val engine = engineFactory.load(directory)
        try {
            val renderer = rendererFactory.load(directory)
            return LoadedModel(engine, renderer)
        } catch (error: Throwable) {
            engine.close()
            throw error
        }
    }

    private suspend fun warmUpChat() {
        val provider = chatWarmUpPrompt ?: return
        try {
            val prompt = provider() ?: return
            warmUp(ConversationSlot.MAIN, prompt, skipWhileHeld = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineUnavailableException) {
            Log.i(TAG, "no warm-up, the model is not available: ${error.message}")
        } catch (error: Exception) {
            Log.w(TAG, "warming up the chat's system prompt failed", error)
        }
    }

    /** [warmUp]; with [skipWhileHeld] nothing happens while a call holds the model (checked under the run lock). */
    private suspend fun warmUp(slot: ConversationSlot, prompt: WarmUpPrompt, skipWhileHeld: Boolean) {
        withModel { loadedModel ->
            if (skipWhileHeld && holds.get() > 0) {
                return@withModel
            }
            val conversation = conversationOf(loadedModel, slot)
            runCancellable(conversation, owner = null) {
                prefillUnlessCached(loadedModel, slot, conversation, prompt)
            }
        }
    }

    /** On the engine thread: prefills [prompt] unless the cache already starts with it. */
    private fun prefillUnlessCached(
        loadedModel: LoadedModel,
        slot: ConversationSlot,
        conversation: Conversation,
        prompt: WarmUpPrompt,
    ) {
        val rendered = loadedModel.renderer.render(prompt.messages, prompt.tools, addGenerationPrompt = false)
        val lastPrompt = loadedModel.lastPrompts[slot]
        val isCached = lastPrompt != null && startsWith(lastPrompt, rendered)
        if (isCached) {
            return
        }

        loadedModel.lastPrompts.remove(slot)
        val isComplete = conversation.prefill(rendered, SILENT_LISTENER)
        if (isComplete) {
            loadedModel.lastPrompts[slot] = rendered
        }
    }

    /**
     * Runs [block] with the loaded model and the run lock held. An unload between loading and
     * taking the lock is noticed and the model loaded again.
     */
    private suspend fun <T> withModel(block: suspend (LoadedModel) -> T): T {
        while (true) {
            val loadedModel = awaitLoaded()
            runMutex.lock()
            try {
                if (loaded !== loadedModel) {
                    continue
                }
                cancelIdleUnload()
                return block(loadedModel)
            } finally {
                runMutex.unlock()
                scheduleIdleUnloadIfHidden()
            }
        }
    }

    /**
     * The [slot] conversation, made on first use. Its cache takes hundreds of megabytes; when they
     * cannot be had, the model is freed and reported as failed (asking again retries).
     */
    private suspend fun conversationOf(loadedModel: LoadedModel, slot: ConversationSlot): Conversation {
        val existing = loadedModel.conversations[slot]
        if (existing != null) {
            return existing
        }
        val created = try {
            withContext(engineDispatcher) { loadedModel.engine.newConversation(slot.capacity) }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "no memory for the ${slot.name} conversation's cache", error)
            release(loadedModel)
            val message = "not enough memory for the model"
            mutableState.value = EngineState.Failed(message)
            throw EngineUnavailableException(message, error)
        }
        loadedModel.conversations[slot] = created
        return created
    }

    /**
     * Forgets [loadedModel] and frees it, both on the engine thread: a load that starts meanwhile
     * opens the next model there only once this one is closed. Not cancellable once begun, so the
     * model is never forgotten without being freed. The run lock is held.
     */
    private suspend fun release(loadedModel: LoadedModel) {
        withContext(NonCancellable + engineDispatcher) {
            if (loaded === loadedModel) {
                loaded = null
            }
            loadedModel.close()
        }
    }

    /**
     * A generation ran out of memory (decoding a long clip, say). Its conversation's cache is
     * forgotten, since the failure may have come at any step; the model stays loaded.
     */
    private suspend fun outOfMemory(conversation: Conversation, error: OutOfMemoryError): NotEnoughMemoryException {
        Log.e(TAG, "an answer ran out of memory", error)
        withContext(NonCancellable + engineDispatcher) {
            conversation.reset()
        }
        return NotEnoughMemoryException(error)
    }

    /** True when the cache holds the whole prompt [result] answered: it ran to an end, or got as far as answering. */
    private fun holdsWholePrompt(result: GenerationResult): Boolean {
        val wasCancelled = result.finishReason == FinishReason.CANCELLED
        val hasAnswered = result.content.tokens.isNotEmpty()
        return !wasCancelled || hasAnswered
    }

    private suspend fun generateFitted(
        loadedModel: LoadedModel,
        conversation: Conversation,
        fitted: FittedPrompt,
        request: GenerationRequest,
        listener: GenerationListener,
        speech: SpeechSink?,
        owner: GenerationOwner?,
    ): GenerationResult = coroutineScope {
        if (speech == null) {
            return@coroutineScope runCancellable(conversation, owner) {
                conversation.generate(fitted.prompt, request, listener)
            }
        }

        val frames = Channel<IntArray>(Channel.UNLIMITED)
        val decoder = loadedModel.engine.newSpeechDecoder()
        val decoding = launch(speechDispatcher) { decodeSpeech(decoder, frames, speech) }
        val forwardingListener = SpeechForwardingListener(listener, frames)
        try {
            runCancellable(conversation, owner) {
                conversation.generate(fitted.prompt, request, forwardingListener)
            }
        } finally {
            frames.close()
            decoding.join()
        }
    }

    /** Decodes frames as they arrive; an empty frame marks the end of a stretch of speech. */
    private suspend fun decodeSpeech(decoder: SpeechDecoder, frames: Channel<IntArray>, sink: SpeechSink) {
        try {
            for (codes in frames) {
                val samples = if (codes.isEmpty()) decoder.finish() else decoder.push(codes)
                if (samples.isNotEmpty()) {
                    sink.onSamples(samples)
                }
            }
            val remaining = decoder.finish()
            if (remaining.isNotEmpty()) {
                sink.onSamples(remaining)
            }
        } catch (error: IllegalStateException) {
            // The engine closed under the decoder (memory ran out): the answer simply ends here
            Log.w(TAG, "speech decoding stopped", error)
        }
    }

    /**
     * Runs [block] on the engine thread as [owner]'s work. Cancelling the calling coroutine
     * cancels the conversation, then waits for the engine to stop before rethrowing.
     */
    private suspend fun <T> runCancellable(
        conversation: Conversation,
        owner: GenerationOwner?,
        block: () -> T,
    ): T = coroutineScope {
        val work = async(engineDispatcher) {
            setRunning(RunningWork(conversation, owner))
            try {
                block()
            } finally {
                setRunning(null)
            }
        }
        try {
            work.await()
        } catch (error: CancellationException) {
            conversation.cancel()
            throw error
        }
    }

    private fun setRunning(work: RunningWork?) {
        synchronized(runningLock) {
            running = work
        }
    }

    private fun scheduleIdleUnloadIfHidden() {
        if (isAppVisible || holds.get() > 0 || loaded == null) {
            return
        }
        synchronized(loadLock) {
            idleJob?.cancel()
            idleJob = scope.launch { unloadWhenIdle() }
        }
    }

    private suspend fun unloadWhenIdle() {
        delay(idleUnloadDelayMs)
        if (isAppVisible || holds.get() > 0) {
            return
        }
        unload()

        // Shown again while the model was being freed: load it back, as showing the app does
        if (isAppVisible) {
            requestLoad()
        }
    }

    private fun cancelIdleUnload() {
        synchronized(loadLock) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    /** The system prompt (and tools) a conversation starts with. */
    class WarmUpPrompt(
        val messages: List<ChatMessage>,
        val tools: List<ToolDefinition>,
    )

    private class LoadedModel(
        val engine: OmniEngine,
        val renderer: PromptRenderer,
    ) {
        val conversations = HashMap<ConversationSlot, Conversation>()

        /** The prompt each conversation's cache is known to start with; none while that is unknown. */
        val lastPrompts = HashMap<ConversationSlot, Prompt>()

        fun close() {
            for (conversation in conversations.values) {
                conversation.close()
            }
            conversations.clear()
            engine.close()
        }
    }

    /** What the engine thread runs now, and for whom. */
    private class RunningWork(val conversation: Conversation, val owner: GenerationOwner?)

    /** Passes everything to [listener] and the speech frames (plus end markers) to [frames]. */
    private class SpeechForwardingListener(
        private val listener: GenerationListener,
        private val frames: Channel<IntArray>,
    ) : GenerationListener {
        override fun onPrefill(done: Int, total: Int) {
            listener.onPrefill(done, total)
        }

        override fun onText(delta: String) {
            listener.onText(delta)
        }

        override fun onAudioFrame(codes: IntArray) {
            frames.trySend(codes.copyOf())
            listener.onAudioFrame(codes)
        }

        override fun onAudioEnd() {
            frames.trySend(END_OF_SPEECH)
            listener.onAudioEnd()
        }
    }

    companion object {
        const val IDLE_UNLOAD_DELAY_MS = 10L * 60 * 1000

        private const val TAG = "AssistantEngine"
        private const val ENGINE_THREAD_NAME = "curie-engine"
        private const val SPEECH_THREAD_NAME = "curie-speech-decoder"

        /** Text to speech runs at about 15 characters a second, 12.5 frames a second: under one frame a character. */
        private const val TTS_FRAMES_PER_CHAR = 2
        private const val TTS_EXTRA_FRAMES = 64

        private val END_OF_SPEECH = IntArray(0)
        private val SILENT_LISTENER = object : GenerationListener {}

        /** The modes whose answers open with a marker token (<|audio_start|> or <|mixed_start|>). */
        private val MARKED_MODES = setOf(GenerationMode.SPEECH, GenerationMode.INTERLEAVED, GenerationMode.SPOKEN)

        /** True when [prompt] begins with every slot of [prefix]. */
        internal fun startsWith(prompt: Prompt, prefix: Prompt): Boolean {
            if (prefix.length > prompt.length) {
                return false
            }
            for (index in 0 until prefix.length) {
                val sameToken = prompt.tokens[index] == prefix.tokens[index]
                val sameSource = prompt.sources[index] == prefix.sources[index]
                if (!sameToken || !sameSource) {
                    return false
                }
            }
            return true
        }

        private fun newThreadDispatcher(name: String): ExecutorCoroutineDispatcher {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, name)
                thread.isDaemon = true
                thread
            }
            return executor.asCoroutineDispatcher()
        }
    }
}
