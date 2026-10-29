package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.prompt.SlotSource

/**
 * How a [GenerationMode] starts after a prompt, as the reference's generation_mode and suppress
 * flags do: the [marker] appended to the prompt (null when none is), the [mode] of the first slot,
 * and the ids text sampling never picks.
 */
internal class GenerationStart(
    val marker: Int?,
    val mode: SlotMode,
    val suppressed: IntArray,
) {
    companion object {
        /**
         * The start of [mode] after a prompt that ends with [lastToken], filled by [lastSource].
         *
         * SPEECH appends <|audio_start|> unless the prompt ends with it or with <|audio_out|>;
         * INTERLEAVED appends <|mixed_start|> unless the prompt ends with it. AUTO reads the mode
         * from the last token as generation_mode=None does: <|audio_start|> or <|audio_out|> speak
         * (but a replayed end-of-speech frame resumes the text), <|mixed_start|> interleaves,
         * anything else is text. TEXT never samples <|mixed_start|> or <|audio_start|>. SPOKEN
         * starts as TEXT does, to read the first token; [SPOKEN_SPEECH] is how it goes on to speak.
         */
        fun of(mode: GenerationMode, lastToken: Int, lastSource: SlotSource?): GenerationStart =
            when (mode) {
                GenerationMode.TEXT, GenerationMode.SPOKEN -> GenerationStart(null, SlotMode.TEXT, TEXT_SUPPRESSED)
                GenerationMode.AUTO -> GenerationStart(null, inferredMode(lastToken, lastSource), PLACEHOLDERS)
                GenerationMode.SPEECH -> {
                    val endsWithSpeech = lastToken in SPEECH_MARKERS
                    val marker = if (endsWithSpeech) null else Lfm3Omni.AUDIO_START_TOKEN
                    GenerationStart(marker, SlotMode.AUDIO, PLACEHOLDERS)
                }
                GenerationMode.INTERLEAVED -> {
                    val marker = if (lastToken == Lfm3Omni.MIXED_START_TOKEN) null else Lfm3Omni.MIXED_START_TOKEN
                    GenerationStart(marker, SlotMode.INTERLEAVED, PLACEHOLDERS)
                }
            }

        private fun inferredMode(lastToken: Int, lastSource: SlotSource?): SlotMode =
            when (lastToken) {
                Lfm3Omni.AUDIO_START_TOKEN -> SlotMode.AUDIO
                Lfm3Omni.AUDIO_OUT_TOKEN -> if (isEndOfSpeech(lastSource)) SlotMode.TEXT else SlotMode.AUDIO
                Lfm3Omni.MIXED_START_TOKEN -> SlotMode.INTERLEAVED
                else -> SlotMode.TEXT
            }

        private fun isEndOfSpeech(source: SlotSource?): Boolean {
            val codes = (source as? SlotSource.AudioOutCodes)?.codes ?: return false
            return codes.any { code -> code == Lfm3Omni.AUDIO_EOS_CODE }
        }

        /** The tokens after which a SPEECH answer needs no <|audio_start|>. */
        private val SPEECH_MARKERS = setOf(Lfm3Omni.AUDIO_START_TOKEN, Lfm3Omni.AUDIO_OUT_TOKEN)

        private val PLACEHOLDERS: IntArray = Lfm3Omni.PLACEHOLDER_TOKENS.copyOf()

        /** A SPOKEN answer that does not open with a tool call: INTERLEAVED's start after the prompt. */
        val SPOKEN_SPEECH = GenerationStart(Lfm3Omni.MIXED_START_TOKEN, SlotMode.INTERLEAVED, PLACEHOLDERS)

        private val TEXT_SUPPRESSED: IntArray =
            PLACEHOLDERS + intArrayOf(Lfm3Omni.MIXED_START_TOKEN, Lfm3Omni.AUDIO_START_TOKEN)
    }
}
