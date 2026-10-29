package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolCallParser
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer

/** The ids of the plain (not special) added tokens that the visible text leaves out. */
internal class MarkupTokens(
    val toolCallStart: Int,
    val toolCallEnd: Int,
    val thinkStart: Int,
    val thinkEnd: Int,
) {
    companion object {
        fun of(tokenizer: BpeTokenizer): MarkupTokens =
            MarkupTokens(
                toolCallStart = requireToken(tokenizer, "<tool_call>"),
                toolCallEnd = requireToken(tokenizer, "</tool_call>"),
                thinkStart = requireToken(tokenizer, "<think>"),
                thinkEnd = requireToken(tokenizer, "</think>"),
            )

        private fun requireToken(tokenizer: BpeTokenizer, content: String): Int {
            val id = tokenizer.tokenId(content)
            require(id != null) { "the tokenizer has no $content token" }
            return id
        }
    }
}

/**
 * The visible text of an answer as it streams: the tokenizer's streaming decode with special tokens
 * skipped, leaving out <think> and </think> and everything from <tool_call> to </tool_call>.
 */
internal class VisibleTextStream(
    tokenizer: BpeTokenizer,
    private val markup: MarkupTokens,
    private val onText: (String) -> Unit,
) {
    private val decoder = tokenizer.newStreamingDecoder(skipSpecialTokens = true)
    private var insideToolCall = false

    fun push(token: Int) {
        when {
            token == markup.toolCallStart -> insideToolCall = true
            token == markup.toolCallEnd -> insideToolCall = false
            insideToolCall || token == markup.thinkStart || token == markup.thinkEnd -> Unit
            else -> emit(decoder.push(token))
        }
    }

    /** Emits the text held back for an unfinished character. */
    fun finish() {
        emit(decoder.flush())
    }

    private fun emit(text: String) {
        if (text.isNotEmpty()) {
            onText(text)
        }
    }
}

/**
 * What a generation leaves behind: the [content] a history replays, and the answer's visible
 * [text], [toolCalls] and speech [audioFrames].
 */
internal class GeneratedAnswer(
    val content: GeneratedContent,
    val text: String,
    val toolCalls: List<ToolCall>,
    val audioFrames: List<IntArray>,
) {
    companion object {
        /**
         * The answer after [marker] (the mode marker appended to the prompt, if any) of the loop's
         * [outcome].
         *
         * The content is every slot after the prompt: the marker, the text tokens and an
         * <|audio_out|> per frame (end-of-speech frames included) with its codes, except a final
         * <|im_end|>, which the chat template writes after the content. An answer cut inside speech
         * (any finish but [FinishReason.END_OF_TURN]) is closed as the model would close it: inside
         * an interleaved block whose text goes on with <|text_end|>, then with an end-of-speech
         * frame, so that the history replays a well-formed turn.
         */
        fun of(marker: Int?, outcome: LoopOutcome, tokenizer: BpeTokenizer, markup: MarkupTokens): GeneratedAnswer {
            val content = contentOf(marker, outcome)

            val audioFrames = ArrayList<IntArray>()
            val textTokens = ArrayList<Int>()
            for (slot in outcome.slots) {
                val frame = slot.codes
                if (frame == null) {
                    textTokens.add(slot.token)
                } else if (!slot.isEndOfSpeech) {
                    audioFrames.add(frame)
                }
            }

            val text = visibleText(textTokens, tokenizer, markup)
            val toolCalls = toolCalls(textTokens, tokenizer)
            return GeneratedAnswer(content, text, toolCalls, audioFrames)
        }

        private fun contentOf(marker: Int?, outcome: LoopOutcome): GeneratedContent {
            val tokens = ArrayList<Int>()
            val codes = ArrayList<Int>()
            if (marker != null) {
                tokens.add(marker)
            }
            for (slot in outcome.slots) {
                if (slot.token == Lfm3Omni.IM_END_TOKEN) {
                    continue
                }
                tokens.add(slot.token)
                val frame = slot.codes ?: continue
                for (code in frame) {
                    codes.add(code)
                }
            }

            val cut = outcome.finishReason != FinishReason.END_OF_TURN
            val insideInterleaved = outcome.endMode == SlotMode.INTERLEAVED
            if (cut && insideInterleaved && !outcome.textDone) {
                tokens.add(Lfm3Omni.TEXT_END_TOKEN)
            }
            if (cut && (insideInterleaved || outcome.endMode == SlotMode.AUDIO)) {
                tokens.add(Lfm3Omni.AUDIO_OUT_TOKEN)
                repeat(Lfm3Omni.NUM_CODEBOOKS) { codes.add(Lfm3Omni.AUDIO_EOS_CODE) }
            }
            return GeneratedContent(tokens.toIntArray(), codes.toIntArray())
        }

        /** The decoded text without special tokens, <think> tags and tool call markup, trimmed. */
        private fun visibleText(textTokens: List<Int>, tokenizer: BpeTokenizer, markup: MarkupTokens): String {
            val shown = textTokens.filter { token -> token != markup.thinkStart && token != markup.thinkEnd }
            val decoded = tokenizer.decode(shown.toIntArray(), skipSpecialTokens = true)
            return ToolCallParser.stripToolCalls(decoded)
        }

        private fun toolCalls(textTokens: List<Int>, tokenizer: BpeTokenizer): List<ToolCall> {
            val decoded = tokenizer.decode(textTokens.toIntArray(), skipSpecialTokens = true)
            return ToolCallParser.parse(decoded)
        }
    }
}
