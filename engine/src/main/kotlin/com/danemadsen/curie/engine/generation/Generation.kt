package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.chat.ToolCall

/** How the answer is produced (the reference generate()'s generation_mode). */
enum class GenerationMode {
    /** Text only: <|mixed_start|> and <|audio_start|> are suppressed (tool calls stay possible). */
    TEXT,

    /** The model picks: plain text, a tool call, speech or interleaved text and speech. */
    AUTO,

    /** Speech only: the answer opens with <|audio_start|> (text to speech). */
    SPEECH,

    /** Interleaved text and speech: the answer opens with <|mixed_start|>. */
    INTERLEAVED,

    /**
     * An answer meant to be heard: interleaved text and speech like [INTERLEAVED], unless the model
     * opens it with a tool call, which then runs as [TEXT] would. The likeliest first text token
     * decides (forced interleaving never calls tools, and AUTO rarely chooses to speak). Its speech
     * is in the base model's voice: the bundled Curie LoRA only changes the voice of [SPEECH].
     */
    SPOKEN,
}

/** Text token sampling; the model card recommends greedy decoding, the default. */
data class TextSampling(
    val temperature: Float = 0f,
    val topK: Int = 0,
    val repetitionPenalty: Float = 1f,
    val repetitionWindow: Int = 32,
)

/** Speech code sampling per codebook; the defaults are the checkpoint's generation_config.json. */
data class AudioSampling(
    val temperature: Float = 0.4f,
    val topK: Int = 0,
    val repetitionPenalty: Float = 1.2f,
    val repetitionWindow: Int = 64,
)

data class GenerationRequest(
    val mode: GenerationMode,
    /** Slots: a text token or a speech frame each. */
    val maxNewSlots: Int = 1024,
    val textSampling: TextSampling = TextSampling(),
    val audioSampling: AudioSampling = AudioSampling(),
    /** Seeds the MaskGIT orders and the sampling; null draws a random seed. */
    val seed: Long? = null,

    /**
     * False never samples <tool_call>: the answer is text (or speech) only, for instance after
     * tool results that the caller wants answered rather than followed by more calls.
     */
    val allowToolCalls: Boolean = true,
)

/** Callbacks of a running generation, on the generating thread. */
interface GenerationListener {
    /** [done] of [total] prompt positions are in the cache (media encoding counts as progress). */
    fun onPrefill(done: Int, total: Int) {}

    /** New visible text: special tokens and <think></think> are left out. */
    fun onText(delta: String) {}

    /** A speech frame (12 codes) other than the end-of-speech frame. */
    fun onAudioFrame(codes: IntArray) {}

    /** The end-of-speech frame closed a stretch of speech. */
    fun onAudioEnd() {}
}

enum class FinishReason {
    /** <|im_end|> (or <|text_end|> outside an interleaved answer) ended the turn. */
    END_OF_TURN,
    MAX_SLOTS,
    CONTEXT_FULL,
    CANCELLED,
}

class GenerationResult(
    /** What to store in the history so a later prompt replays this turn exactly. */
    val content: GeneratedContent,
    /** The visible text of the answer, without tool call markup. */
    val text: String,
    /** The tool calls the answer made, in order. */
    val toolCalls: List<ToolCall>,
    /** The answer's speech frames (12 codes each, end-of-speech frames left out). */
    val audioFrames: List<IntArray>,
    val finishReason: FinishReason,
)
