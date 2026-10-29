package com.danemadsen.curie.inference

import com.danemadsen.curie.engine.ContextOverflowException
import com.danemadsen.curie.engine.Conversation
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.OmniEngine
import com.danemadsen.curie.engine.SpeechDecoder
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.prompt.MediaInput
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.SlotSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the fake model answers next. */
data class ScriptedAnswer(
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),

    /** Speech frames the answer says (each decodes to 80 ms of samples). */
    val audioFrames: Int = 0,

    /** Streams the first word, then waits for [Conversation.cancel] and ends CANCELLED. */
    val waitsForCancel: Boolean = false,

    /** Streams the first word, then waits for this latch before finishing. */
    val pauseAfterFirstWord: CountDownLatch? = null,

    /** Thrown instead of answering, as a decoder or a media loader might. */
    val failure: Throwable? = null,
)

/**
 * An [OmniEngine] that answers from a script instead of a model. Its conversations check the
 * capacity like the real ones, stream the scripted text word by word, and record every prompt.
 */
class FakeOmniEngine : OmniEngine {
    private val script = LinkedBlockingQueue<ScriptedAnswer>()

    val conversations = ArrayList<FakeConversation>()
    var isClosed = false
        private set

    fun answer(answer: ScriptedAnswer) {
        script.add(answer)
    }

    fun answer(text: String) {
        script.add(ScriptedAnswer(text))
    }

    override fun newConversation(capacity: Int): Conversation {
        val conversation = FakeConversation(capacity, script)
        synchronized(conversations) {
            conversations.add(conversation)
        }
        return conversation
    }

    override fun newSpeechDecoder(): SpeechDecoder = FakeSpeechDecoder()

    override fun close() {
        isClosed = true
    }
}

class FakeConversation(
    override val capacity: Int,
    private val script: LinkedBlockingQueue<ScriptedAnswer>,
) : Conversation {
    @Volatile
    private var isCancelled = false

    @Volatile
    private var cancelled = CountDownLatch(1)

    val generations = ArrayList<Pair<Prompt, GenerationRequest>>()
    val prefills = ArrayList<Prompt>()

    /** The next prefill stops early, as a cancelled one does. */
    @Volatile
    var nextPrefillIsCancelled = false

    @Volatile
    var resets = 0
        private set

    override var cachedLength: Int = 0
        private set

    override fun generate(prompt: Prompt, request: GenerationRequest, listener: GenerationListener): GenerationResult {
        val required = prompt.length + request.maxNewSlots
        if (required > capacity) {
            throw ContextOverflowException(required, capacity)
        }
        synchronized(generations) {
            generations.add(prompt to request)
        }
        isCancelled = false
        cancelled = CountDownLatch(1)

        val answer = script.poll(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: ScriptedAnswer("(no scripted answer)")
        if (answer.failure != null) {
            throw answer.failure
        }
        val words = answer.text.split(" ").filter { word -> word.isNotEmpty() }
        val spoken = StringBuilder()
        var finishReason = FinishReason.END_OF_TURN
        for ((index, word) in words.withIndex()) {
            val delta = if (index == 0) word else " $word"
            listener.onText(delta)
            spoken.append(delta)
            if (index == 0) {
                answer.pauseAfterFirstWord?.await(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (answer.waitsForCancel) {
                    cancelled.await(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            if (isCancelled) {
                finishReason = FinishReason.CANCELLED
                break
            }
        }

        val frames = ArrayList<IntArray>()
        if (!isCancelled) {
            repeat(answer.audioFrames) { frame ->
                val codes = IntArray(Lfm3Omni.NUM_CODEBOOKS) { codebook -> (frame + codebook) % 100 }
                listener.onAudioFrame(codes)
                frames.add(codes)
            }
            if (answer.audioFrames > 0) {
                listener.onAudioEnd()
            }
        }

        val text = spoken.toString()
        val toolCalls = if (finishReason == FinishReason.CANCELLED) emptyList() else answer.toolCalls
        val tokens = IntArray(text.length + frames.size)
        for (index in text.indices) {
            tokens[index] = text[index].code
        }
        for (index in frames.indices) {
            tokens[text.length + index] = Lfm3Omni.AUDIO_OUT_TOKEN
        }
        val audioCodes = IntArray(frames.size * Lfm3Omni.NUM_CODEBOOKS)
        for ((index, codes) in frames.withIndex()) {
            codes.copyInto(audioCodes, destinationOffset = index * Lfm3Omni.NUM_CODEBOOKS)
        }
        val content = GeneratedContent(tokens, audioCodes)
        cachedLength = prompt.length + tokens.size
        return GenerationResult(content, text, toolCalls, frames, finishReason)
    }

    override fun prefill(prompt: Prompt, listener: GenerationListener): Boolean {
        synchronized(prefills) {
            prefills.add(prompt)
        }
        if (nextPrefillIsCancelled) {
            nextPrefillIsCancelled = false
            return false
        }
        cachedLength = prompt.length
        return true
    }

    override fun cancel() {
        isCancelled = true
        cancelled.countDown()
    }

    override fun reset() {
        resets++
        cachedLength = 0
    }

    override fun close() {}

    private companion object {
        const val SCRIPT_TIMEOUT_SECONDS = 10L
    }
}

/** Every frame decodes to 80 ms of a constant level. */
class FakeSpeechDecoder : SpeechDecoder {
    override fun push(codes: IntArray): FloatArray = FloatArray(Lfm3Omni.FRAME_SAMPLES) { 0.25f }

    override fun finish(): FloatArray = FloatArray(0)
}

/**
 * Renders like the chat template does in size and checkpoints, without a tokenizer: one slot per
 * character of the system prompt and [slotsPerChar] per character of the other messages' text, a
 * few for each message's markers, one per media row and the generated tokens as they are. It keeps
 * the messages of its last prompt for an answer (a warm-up, rendered without the generation
 * prompt and maybe in between, does not count).
 */
class FakePromptRenderer(private val slotsPerChar: Int = 1) : PromptRenderer {
    @Volatile
    var lastMessages: List<ChatMessage> = emptyList()
        private set

    override fun render(messages: List<ChatMessage>, tools: List<ToolDefinition>, addGenerationPrompt: Boolean): Prompt {
        if (addGenerationPrompt) {
            lastMessages = messages
        }
        val slots = ArrayList<Int>()
        val checkpoints = ArrayList<Int>()
        val media = LinkedHashMap<String, MediaInput>()
        slots.add(Lfm3Omni.BOS_TOKEN)

        for ((index, message) in messages.withIndex()) {
            checkpoints.add(slots.size)
            repeat(MESSAGE_MARKER_SLOTS) { slots.add(Lfm3Omni.IM_START_TOKEN) }
            val isSystem = index == 0 && message.role == ChatRole.SYSTEM
            if (isSystem) {
                repeat(tools.size) { slots.add(TOOL_SLOT) }
            }
            val textCost = if (isSystem) 1 else slotsPerChar
            addContent(message, textCost, slots, media)
        }
        if (addGenerationPrompt) {
            checkpoints.add(slots.size)
            repeat(MESSAGE_MARKER_SLOTS) { slots.add(Lfm3Omni.IM_START_TOKEN) }
        }
        checkpoints.add(slots.size)

        val tokens = slots.toIntArray()
        val sources = arrayOfNulls<SlotSource>(tokens.size)
        return Prompt(tokens, sources, checkpoints.toIntArray(), media)
    }

    private fun addContent(
        message: ChatMessage,
        slotsPerChar: Int,
        slots: MutableList<Int>,
        media: MutableMap<String, MediaInput>,
    ) {
        val generated = message.generated
        if (generated != null) {
            for (token in generated.tokens) {
                slots.add(if (token == Lfm3Omni.AUDIO_OUT_TOKEN) TEXT_SLOT else token)
            }
            return
        }
        for (part in message.parts) {
            when (part) {
                is ContentPart.Text -> repeat(part.text.length * slotsPerChar) { slots.add(TEXT_SLOT) }
                is ContentPart.Audio -> {
                    media[part.input.key] = part.input
                    repeat(part.input.sampleCount / AUDIO_SAMPLES_PER_SLOT + 1) { slots.add(Lfm3Omni.AUDIO_TOKEN) }
                }
                is ContentPart.Image -> {
                    media[part.input.key] = part.input
                    repeat(IMAGE_SLOTS) { slots.add(Lfm3Omni.IMAGE_TOKEN) }
                }
                is ContentPart.Video -> {
                    media[part.input.key] = part.input
                    repeat(IMAGE_SLOTS) { slots.add(Lfm3Omni.VIDEO_TOKEN) }
                }
            }
        }
        for (call in message.toolCalls) {
            repeat(call.name.length) { slots.add(TEXT_SLOT) }
        }
    }

    companion object {
        const val MESSAGE_MARKER_SLOTS = 3
        const val TEXT_SLOT = 1000
        const val TOOL_SLOT = 2000
        const val IMAGE_SLOTS = 64

        /** 12.5 audio tokens a second, as the audio encoder gives. */
        const val AUDIO_SAMPLES_PER_SLOT = 1280
    }
}

/** A model whose files are always ready, in a folder nobody reads. */
class FakeModelSource(override val modelDirectory: File = File("fake-model")) : ModelSource {
    var isReady = true
    var ensureReadyCalls = 0
        private set

    override suspend fun ensureReady(): Boolean {
        ensureReadyCalls++
        return isReady
    }
}

/** The text of a message's text parts. */
fun ChatMessage.text(): String {
    val texts = parts.filterIsInstance<ContentPart.Text>()
    return texts.joinToString("") { part -> part.text }
}
