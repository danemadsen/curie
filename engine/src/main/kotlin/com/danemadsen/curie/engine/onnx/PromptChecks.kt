package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.MediaInput
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.SlotSource

/** The checks a prompt passes before it touches a cache, and the media its positions need. */
internal object PromptChecks {
    /**
     * Requires every placeholder to carry the source its decoder input needs (a row of the right
     * stream of media in [Prompt.media], within the media's row count, or a speech frame's 12 codes
     * below [Lfm3Omni.CODEBOOK_SIZE]) and every other position to carry none.
     *
     * @throws IllegalArgumentException naming the first position that does not
     */
    fun validate(prompt: Prompt) {
        val rowCounts = HashMap<Pair<String, FeatureStream>, Int>()
        for (position in 0 until prompt.length) {
            val token = prompt.tokens[position]
            val source = prompt.sources[position]
            val stream = streamOf(token)
            when {
                stream != null -> {
                    require(source is SlotSource.MediaRow && source.stream == stream) {
                        "position $position: placeholder $token needs a ${stream.name.lowercase()} row, has $source"
                    }
                    val input = prompt.media[source.mediaKey]
                    require(input != null) { "position $position: media ${source.mediaKey} is not in the prompt" }
                    val rowCount = rowCounts.getOrPut(source.mediaKey to stream) {
                        MediaFeatures.expectedRows(input, stream)
                    }
                    require(source.row in 0 until rowCount) {
                        "position $position: row ${source.row} of media ${source.mediaKey}, which has $rowCount"
                    }
                }
                token == Lfm3Omni.AUDIO_OUT_TOKEN -> {
                    require(source is SlotSource.AudioOutCodes) { "position $position: <|audio_out|> needs its codes" }
                    requireFrame(source.codes, position)
                }
                else -> require(source == null) {
                    "position $position: token $token is not a placeholder but has $source"
                }
            }
        }
    }

    /**
     * The media streams whose placeholders lie in positions [from] until [to] of [target], each
     * once, in the order they first appear.
     */
    fun mediaStreams(
        target: SlotSequence,
        from: Int,
        to: Int,
        media: Map<String, MediaInput>,
    ): List<Pair<MediaInput, FeatureStream>> {
        val streams = LinkedHashMap<Pair<String, FeatureStream>, MediaInput>()
        for (position in from until to) {
            val source = target.sources[position] as? SlotSource.MediaRow ?: continue
            val key = source.mediaKey to source.stream
            if (key !in streams) {
                streams[key] = checkNotNull(media[source.mediaKey]) { "media ${source.mediaKey} is not in the prompt" }
            }
        }
        return streams.map { (key, input) -> input to key.second }
    }

    private fun streamOf(token: Int): FeatureStream? =
        when (token) {
            Lfm3Omni.IMAGE_TOKEN -> FeatureStream.IMAGE
            Lfm3Omni.VIDEO_TOKEN -> FeatureStream.VIDEO
            Lfm3Omni.AUDIO_TOKEN -> FeatureStream.AUDIO
            else -> null
        }

    private fun requireFrame(codes: IntArray, position: Int) {
        require(codes.size == Lfm3Omni.NUM_CODEBOOKS) {
            "position $position: a frame has ${Lfm3Omni.NUM_CODEBOOKS} codes, got ${codes.size}"
        }
        for (code in codes) {
            require(code in 0 until Lfm3Omni.CODEBOOK_SIZE) {
                "position $position: speech codes are in 0 until ${Lfm3Omni.CODEBOOK_SIZE}, got $code"
            }
        }
    }
}
