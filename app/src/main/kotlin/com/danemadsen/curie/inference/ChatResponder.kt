package com.danemadsen.curie.inference

import android.content.Intent
import com.danemadsen.curie.audio.SampleBuffer
import com.danemadsen.curie.data.ChatRepository
import com.danemadsen.curie.data.Message
import com.danemadsen.curie.data.MessageRole
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** How an answer in a chat is produced. */
data class ResponseOptions(
    /** TEXT for typed messages; AUTO lets the model answer a voice message with speech. */
    val mode: GenerationMode,

    /** The user's name for the system prompt. */
    val userName: String,
)

/** What answering produced. */
class ChatResponse(
    /** The last assistant message of the answer (tool rounds add one each). */
    val finalMessageId: String,

    /** The visible text of that last message. */
    val finalText: String,

    /** The 24 kHz samples of everything the answer said aloud; empty when it only wrote. */
    val speech: FloatArray,

    /** False when the answer was stopped or failed (its error is stored on the message). */
    val isComplete: Boolean,

    /** Runtime permissions a tool lacked; ask for them so the next try can succeed. */
    val permissionsNeeded: Set<String>,

    /**
     * The apps the answer's tools open once it is finished (and has been spoken), in call order and
     * each once; start them with [ChatResponder.openApps]. Empty unless [isComplete].
     */
    val appLaunches: List<Intent> = emptyList(),
)

/**
 * Answers in a stored chat: renders the thread before the answer's message, generates into that
 * message (streaming its text into the database at most every [writeIntervalMs]), runs the tools
 * the model calls and generates again after them (up to [MAX_TOOL_ROUNDS] times), and stores
 * what failed as the message's error.
 */
class ChatResponder(
    private val chats: ChatRepository,
    private val engine: AssistantEngine,
    private val tools: ToolExecutor,
    private val history: ChatHistory,
    private val describeError: (Throwable) -> String,

    /** The summary of tool calls left undone because the answer ran out of rounds. */
    private val skippedToolSummary: String,

    private val today: () -> LocalDate,
    private val writeIntervalMs: Long = WRITE_INTERVAL_MS,
) {
    /** Where each chat's oldest turns were cut, so the cut holds while the chat grows. */
    private val windows = HashMap<String, ContextWindow>()

    /** A new answer's stop switch: pass it to [respond], call [AnswerControl.stop] to stop it. */
    fun newAnswer(): AnswerControl = AnswerControl(engine)

    /**
     * Generates into the (empty) assistant message [messageId] of [chatId]. [control] stops it,
     * even before its generation has started.
     */
    suspend fun respond(
        chatId: String,
        messageId: String,
        options: ResponseOptions,
        control: AnswerControl = newAnswer(),
    ): ChatResponse {
        val speech = SampleBuffer(initialCapacity = 0)
        val permissionsNeeded = LinkedHashSet<String>()
        val appLaunches = ArrayList<Intent>()
        val isWebRequested = userAsksForWeb(chatId, messageId)
        var allowToolCalls = true
        var currentId = messageId
        var round = 0

        while (true) {
            if (control.isStopped) {
                return ChatResponse(currentId, "", speech.toArray(), isComplete = false, permissionsNeeded)
            }
            val result = try {
                generateInto(chatId, currentId, options, speech, control, allowToolCalls)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = describeError(error)
                chats.updateMessage(currentId, error = message)
                return ChatResponse(currentId, "", speech.toArray(), isComplete = false, permissionsNeeded)
            }

            // An answer stopped before its first token has nothing to replay
            val hasContent = result.content.tokens.isNotEmpty()
            val generated = if (hasContent) result.content else null
            val isStored = chats.updateMessage(currentId, text = result.text, generated = generated)
            val wasStopped = control.isStopped || result.finishReason == FinishReason.CANCELLED
            if (!isStored || wasStopped) {
                return ChatResponse(currentId, result.text, speech.toArray(), isComplete = false, permissionsNeeded)
            }

            val hasToolCalls = result.toolCalls.isNotEmpty()
            if (hasToolCalls && round >= MAX_TOOL_ROUNDS) {
                // Out of rounds: the calls show as not done instead of the answer looking empty
                val skipped = result.toolCalls.map { call -> skippedRecord(call) }
                chats.updateMessage(currentId, toolCalls = skipped)
            }
            if (!hasToolCalls || round >= MAX_TOOL_ROUNDS) {
                return ChatResponse(
                    finalMessageId = currentId,
                    finalText = result.text,
                    speech = speech.toArray(),
                    isComplete = true,
                    permissionsNeeded = permissionsNeeded,
                    appLaunches = appLaunches,
                )
            }

            val ranOutcomes = runTools(result.toolCalls)
            val outcomes = ranOutcomes.map { outcome -> WebLookups.applyTo(outcome, isWebRequested) }

            // It cannot read what a lookup found: unless it answers next, it looks the same thing up again
            allowToolCalls = outcomes.none { outcome -> outcome.isWebLookup }
            for (outcome in outcomes) {
                permissionsNeeded.addAll(outcome.permissionsNeeded)
                val launch = outcome.deferredLaunch ?: continue
                val isRepeat = appLaunches.any { earlier -> earlier.filterEquals(launch) }
                if (!isRepeat) {
                    appLaunches.add(launch)
                }
            }
            val records = recordsOf(result.toolCalls, outcomes)
            chats.updateMessage(currentId, toolCalls = records)

            val resultsText = ToolResults.toolMessageText(outcomes)
            val toolMessage = chats.appendMessage(chatId, currentId, MessageRole.TOOL, resultsText)
            val nextAnswer = chats.appendMessage(chatId, toolMessage.id, MessageRole.ASSISTANT, "")
            currentId = nextAnswer.id
            round++
        }
    }

    /**
     * Starts the apps a finished answer's tools deferred, in call order (the last one ends up in
     * front). False when Android blocked one because Curie was no longer on screen.
     */
    fun openApps(response: ChatResponse): Boolean {
        var isEveryAppOpen = true
        for (intent in response.appLaunches) {
            val isStarted = tools.startDeferredLaunch(intent)
            if (!isStarted) {
                isEveryAppOpen = false
            }
        }
        return isEveryAppOpen
    }

    /** Opens the web search or page a held-back lookup [record] stands for; false when it could not. */
    fun openLink(record: ToolCallRecord): Boolean {
        val arguments = try {
            Json.parseToJsonElement(record.argumentsJson) as? JsonObject
        } catch (error: SerializationException) {
            null
        }
        if (arguments == null) {
            return false
        }
        val call = ToolCall(record.name, arguments)
        val link = tools.webLinkOf(call) ?: return false
        return tools.startDeferredLaunch(link)
    }

    /** Whether the user's message that [messageId] answers asks for the web in so many words. */
    private suspend fun userAsksForWeb(chatId: String, messageId: String): Boolean {
        val thread = chats.getThread(chatId)
        val answerIndex = thread.indexOfFirst { item -> item.message.id == messageId }
        val before = if (answerIndex >= 0) thread.subList(0, answerIndex) else thread
        val question = before.lastOrNull { item -> item.message.role == MessageRole.USER } ?: return false
        return WebLookups.isRequestedIn(question.message.text)
    }

    private suspend fun generateInto(
        chatId: String,
        messageId: String,
        options: ResponseOptions,
        speech: SampleBuffer,
        control: AnswerControl,
        allowToolCalls: Boolean,
    ): GenerationResult = coroutineScope {
        val messages = promptMessages(chatId, messageId, options)
        val request = GenerationRequest(
            mode = options.mode,
            maxNewSlots = ANSWER_SLOTS,
            allowToolCalls = allowToolCalls,
        )
        val streamed = StreamedText(this, messageId, control)

        // A typed message gets a written answer: TEXT mode cannot start speech, so nothing to decode
        val speechSink = if (options.mode == GenerationMode.TEXT) {
            null
        } else {
            SpeechSink { samples -> speech.appendSynchronized(samples) }
        }
        val window = synchronized(windows) { windows.getOrPut(chatId) { ContextWindow() } }
        try {
            engine.generate(
                slot = ConversationSlot.MAIN,
                messages = messages,
                tools = tools.definitions,
                request = request,
                listener = streamed.listener,
                speech = speechSink,
                window = window,
                owner = control.owner,
            )
        } finally {
            streamed.stop()
        }
    }

    /** The system prompt and the visible thread up to (not including) [messageId]. */
    private suspend fun promptMessages(
        chatId: String,
        messageId: String,
        options: ResponseOptions,
    ): List<ChatMessage> {
        val thread = chats.getThread(chatId)
        val answerIndex = thread.indexOfFirst { item -> item.message.id == messageId }
        check(answerIndex >= 0) { "message $messageId is not in the visible thread of chat $chatId" }
        val before = thread.subList(0, answerIndex)

        val assistantIds = before.filter { item -> item.message.role == MessageRole.ASSISTANT }
        val ids = assistantIds.map { item -> item.message.id }
        val generated = chats.loadGeneratedContent(ids)
        val systemPrompt = SystemPrompts.chat(options.userName, today())
        return history.toChatMessages(systemPrompt, before, generated)
    }

    private suspend fun runTools(calls: List<ToolCall>): List<ToolOutcome> {
        val outcomes = ArrayList<ToolOutcome>(calls.size)
        for (call in calls) {
            val outcome = tools.execute(call, ToolOrigin.CHAT)
            outcomes.add(outcome)
        }
        return outcomes
    }

    private fun recordsOf(calls: List<ToolCall>, outcomes: List<ToolOutcome>): List<ToolCallRecord> {
        return calls.mapIndexed { index, call ->
            val outcome = outcomes.getOrNull(index)
            ToolResults.record(call, outcome)
        }
    }

    private fun skippedRecord(call: ToolCall): ToolCallRecord {
        val record = ToolResults.record(call, outcome = null)
        return record.copy(summary = skippedToolSummary)
    }

    private fun SampleBuffer.appendSynchronized(samples: FloatArray) {
        synchronized(this) {
            append(samples)
        }
    }

    /**
     * The answer's text as it streams in, written to its message at most every [writeIntervalMs].
     * Its listener also enforces a stop that came before the generation was running.
     */
    private inner class StreamedText(
        scope: CoroutineScope,
        private val messageId: String,
        private val control: AnswerControl,
    ) {
        private val text = StringBuilder()
        private val latest = MutableStateFlow("")

        val listener = object : GenerationListener {
            override fun onPrefill(done: Int, total: Int) {
                control.enforce()
            }

            override fun onText(delta: String) {
                control.enforce()
                synchronized(text) {
                    text.append(delta)
                    latest.value = text.toString()
                }
            }

            override fun onAudioFrame(codes: IntArray) {
                control.enforce()
            }
        }

        private val writer = scope.launch {
            val changes = latest.drop(1)
            changes.collect { current ->
                chats.updateMessage(messageId, text = current)
                delay(writeIntervalMs)
            }
        }

        suspend fun stop() {
            writer.cancelAndJoin()
        }
    }

    /**
     * Stops one answer: its generation ends early and no tool round follows. It stops only this
     * answer's own generations, never another one the engine runs (a call's, a warm-up).
     */
    class AnswerControl internal constructor(private val engine: AssistantEngine) {
        internal val owner = GenerationOwner()

        @Volatile
        var isStopped = false
            private set

        fun stop() {
            isStopped = true
            engine.cancel(owner)
        }

        /** Called from the generation: a stop that came before it was running takes effect now. */
        internal fun enforce() {
            if (isStopped) {
                engine.cancel(owner)
            }
        }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 4

        /** Up to about a thousand tokens of answer (or a minute of interleaved speech). */
        const val ANSWER_SLOTS = 1024

        const val WRITE_INTERVAL_MS = 100L
    }
}
