package com.danemadsen.curie.engine.prompt

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.MediaLimits
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import com.danemadsen.curie.engine.tokenizer.LiteralText

/**
 * Assembles a [Prompt] slot by slot: text, tokens, replayed answers and media, with the placeholder
 * expansions of Lfm3OmniProcessor (one placeholder per feature row, wrapped in
 * <|vision_start|>...<|vision_end|>).
 *
 * Text is collected and tokenized as one string when anything else is added, so consecutive [text]
 * and [literalText] calls tokenize like their concatenation, as the tokenizer would see the rendered
 * template. Special-token strings such as "<|im_end|>" become those tokens in [text] and stay text
 * in [literalText]. Media placeholder strings (such as "<|image|>") stay text in both: only [image],
 * [video], [audio] and [generated] produce placeholders, each with its feature row.
 *
 * A call that throws leaves the builder as it was. Not thread-safe.
 */
class PromptBuilder(private val tokenizer: BpeTokenizer) {
    private val tokens = ArrayList<Int>()
    private val sources = ArrayList<SlotSource?>()
    private val checkpoints = ArrayList<Int>()
    private val media = LinkedHashMap<String, MediaInput>()
    private val pendingText = StringBuilder()

    /** Where [literalText] put its text in [pendingText]. */
    private val pendingLiteralRanges = ArrayList<IntRange>()

    /** Adds text of the prompt's own format: special-token strings in it become those tokens. */
    fun text(text: String) {
        pendingText.append(text)
    }

    /**
     * Adds text the prompt does not control, such as a message or a tool result: special-token
     * strings in it stay text, so it cannot end a turn or start one. It tokenizes as tokenizers does
     * with encode_special_tokens (transformers' split_special_tokens): added tokens that are not
     * special, such as <think> and <tool_call>, still match, and text without special-token strings
     * tokenizes exactly as with [text].
     */
    fun literalText(text: String) {
        if (text.isEmpty()) {
            return
        }
        val start = pendingText.length
        pendingText.append(text)
        pendingLiteralRanges.add(start until pendingText.length)
    }

    /** Adds one token id (not a media placeholder: those come with their features). */
    fun token(id: Int) {
        requireNotPlaceholder(id)

        flushText()
        append(id, null)
    }

    fun tokens(ids: IntArray) {
        for (id in ids) {
            requireNotPlaceholder(id)
        }

        flushText()
        for (id in ids) {
            append(id, null)
        }
    }

    /**
     * Replays an answer's tokens verbatim; each <|audio_out|> takes the next 12 codes of
     * [GeneratedContent.audioCodes].
     */
    fun generated(content: GeneratedContent) {
        val frameCount = content.audioCodes.size / Lfm3Omni.NUM_CODEBOOKS
        val audioOutCount = content.tokens.count { id -> id == Lfm3Omni.AUDIO_OUT_TOKEN }
        require(audioOutCount == frameCount) { "$audioOutCount <|audio_out|> tokens but $frameCount frames of codes" }
        for (id in content.tokens) {
            if (id != Lfm3Omni.AUDIO_OUT_TOKEN) {
                requireNotPlaceholder(id)
            }
        }

        flushText()
        var frame = 0
        for (id in content.tokens) {
            if (id != Lfm3Omni.AUDIO_OUT_TOKEN) {
                append(id, null)
                continue
            }
            val start = frame * Lfm3Omni.NUM_CODEBOOKS
            val codes = content.audioCodes.copyOfRange(start, start + Lfm3Omni.NUM_CODEBOOKS)
            val source = SlotSource.AudioOutCodes(codes)
            append(id, source)
            frame++
        }
    }

    /** <|vision_start|>, one <|image|> per image token, <|vision_end|>. */
    fun image(input: ImageInput) {
        val grid = MediaGeometry.imageGrid(input.width, input.height, MediaLimits.MAX_IMAGE_TOKENS)
        val rows = 0 until grid.tokens
        requireConsistentKey(input)

        flushText()
        recordMedia(input)
        append(Lfm3Omni.VISION_START_TOKEN, null)
        appendRows(Lfm3Omni.IMAGE_TOKEN, input.key, FeatureStream.IMAGE, rows)
        append(Lfm3Omni.VISION_END_TOKEN, null)
    }

    /**
     * <|vision_start|>, then per group of two frames its "<t seconds>" timestamp and its
     * <|vision_start|> <|video|>... <|vision_end|>, then <|vision_end|>. With [withSoundtrack] (and a
     * soundtrack) each group is followed by the <|audio|> rows of the soundtrack's stretch that
     * starts with the group's first frame (use_audio_in_video).
     */
    fun video(input: VideoInput, withSoundtrack: Boolean) {
        val frameIndices = MediaGeometry.videoFrameIndices(input.frameCount, input.fps, MediaLimits.MAX_VIDEO_FRAMES)
        val size = MediaGeometry.videoResizeTarget(
            input.width,
            input.height,
            frameIndices.size,
            MediaLimits.MAX_VIDEO_TOKENS,
        )
        val groupGrid = MediaGeometry.Grid(size.height / Lfm3Omni.PATCH_SIZE, size.width / Lfm3Omni.PATCH_SIZE)
        val tokensPerGroup = groupGrid.tokens
        val timestamps = MediaGeometry.videoGroupTimestamps(frameIndices, input.fps)
        val timestampTexts = timestamps.map { seconds -> MediaGeometry.timestampText(seconds) }
        val soundtrackBounds = if (withSoundtrack && input.hasSoundtrack) {
            val soundtrackTokens = MediaGeometry.audioTokenCount(input.soundtrackSamples)
            MediaGeometry.soundtrackBounds(frameIndices, input.fps, soundtrackTokens)
        } else {
            null
        }
        requireConsistentKey(input)

        flushText()
        recordMedia(input)
        append(Lfm3Omni.VISION_START_TOKEN, null)
        for ((group, timestampText) in timestampTexts.withIndex()) {
            text(timestampText)
            flushText()

            val firstRow = group * tokensPerGroup
            val videoRows = firstRow until firstRow + tokensPerGroup
            append(Lfm3Omni.VISION_START_TOKEN, null)
            appendRows(Lfm3Omni.VIDEO_TOKEN, input.key, FeatureStream.VIDEO, videoRows)
            append(Lfm3Omni.VISION_END_TOKEN, null)

            if (soundtrackBounds != null) {
                val soundtrackRows = soundtrackBounds[group] until soundtrackBounds[group + 1]
                appendRows(Lfm3Omni.AUDIO_TOKEN, input.key, FeatureStream.AUDIO, soundtrackRows)
            }
        }
        append(Lfm3Omni.VISION_END_TOKEN, null)
    }

    /** <|vision_start|>, one <|audio|> per audio encoder token, <|vision_end|>. */
    fun audio(input: AudioInput) {
        val rowCount = MediaGeometry.audioTokenCount(input.sampleCount)
        val rows = 0 until rowCount
        requireConsistentKey(input)

        flushText()
        recordMedia(input)
        append(Lfm3Omni.VISION_START_TOKEN, null)
        appendRows(Lfm3Omni.AUDIO_TOKEN, input.key, FeatureStream.AUDIO, rows)
        append(Lfm3Omni.VISION_END_TOKEN, null)
    }

    /**
     * Marks the current position as one the conversation can rewind to. It ends the pending text:
     * text before and after a checkpoint is tokenized separately.
     */
    fun checkpoint() {
        flushText()
        if (checkpoints.lastOrNull() != tokens.size) {
            checkpoints.add(tokens.size)
        }
    }

    /** The prompt so far; the builder can go on adding to it. */
    fun build(): Prompt {
        flushText()
        val tokenArray = tokens.toIntArray()
        val sourceArray = sources.toTypedArray()
        val checkpointArray = checkpoints.toIntArray()
        val mediaCopy = LinkedHashMap(media)
        return Prompt(tokenArray, sourceArray, checkpointArray, mediaCopy)
    }

    private fun flushText() {
        if (pendingText.isEmpty()) {
            return
        }
        val text = pendingText.toString()
        val literal = LiteralText(PLACEHOLDER_TOKENS, pendingLiteralRanges.toList())
        pendingText.setLength(0)
        pendingLiteralRanges.clear()

        val ids = tokenizer.encode(text, literal)
        for (id in ids) {
            append(id, null)
        }
    }

    private fun appendRows(placeholder: Int, mediaKey: String, stream: FeatureStream, rows: IntRange) {
        for (row in rows) {
            val source = SlotSource.MediaRow(mediaKey, stream, row)
            append(placeholder, source)
        }
    }

    private fun append(id: Int, source: SlotSource?) {
        tokens.add(id)
        sources.add(source)
    }

    /** Equal keys must mean equal media: the same key cannot name two kinds of media. */
    private fun requireConsistentKey(input: MediaInput) {
        val known = media[input.key] ?: return
        require(known::class == input::class) {
            "media key ${input.key} names both a ${known.javaClass.simpleName} and a ${input.javaClass.simpleName}"
        }
    }

    /** Keeps the first input of each key. */
    private fun recordMedia(input: MediaInput) {
        if (input.key !in media) {
            media[input.key] = input
        }
    }

    private fun requireNotPlaceholder(id: Int) {
        require(id !in PLACEHOLDER_TOKENS) { "placeholder token $id needs its features: add the media instead" }
    }

    private companion object {
        val PLACEHOLDER_TOKENS: Set<Int> = Lfm3Omni.PLACEHOLDER_TOKENS.toSet()
    }
}
