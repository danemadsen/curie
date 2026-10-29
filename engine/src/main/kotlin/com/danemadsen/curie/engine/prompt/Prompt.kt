package com.danemadsen.curie.engine.prompt

/** Which decoder input a placeholder row comes from. */
enum class FeatureStream {
    IMAGE,
    VIDEO,
    AUDIO,
}

/** What fills a placeholder position of a prompt, beyond its token id. */
sealed interface SlotSource {
    /** Row [row] of the [stream] features of the media keyed [mediaKey]. */
    data class MediaRow(
        val mediaKey: String,
        val stream: FeatureStream,
        val row: Int,
    ) : SlotSource

    /** The 12 codes of a speech frame at an <|audio_out|> position (a replayed answer). */
    class AudioOutCodes(val codes: IntArray) : SlotSource {
        override fun equals(other: Any?): Boolean =
            other is AudioOutCodes && codes.contentEquals(other.codes)

        override fun hashCode(): Int = codes.contentHashCode()
    }
}

/**
 * The decoder input of one generation, position by position. Two prompts share a prefix where both
 * the token ids and the sources agree, which lets a conversation keep the cached part.
 *
 * [checkpoints] are the (sorted) positions where a message starts and where the prompt ends: the
 * conversation snapshots its recurrent state there, so it can rewind to them (an edited message,
 * a regenerated answer) without starting over.
 */
class Prompt(
    val tokens: IntArray,
    val sources: Array<SlotSource?>,
    val checkpoints: IntArray,
    val media: Map<String, MediaInput>,
) {
    val length: Int
        get() = tokens.size

    init {
        require(sources.size == tokens.size) { "${tokens.size} tokens but ${sources.size} sources" }
    }
}
