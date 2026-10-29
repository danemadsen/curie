package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.MediaLimits
import com.danemadsen.curie.engine.media.VisionPreprocessor
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.MediaInput
import com.danemadsen.curie.engine.prompt.VideoInput

/**
 * The decoder's feature rows of prompt media, computed on demand and kept in a small LRU cache (by
 * media key and stream, up to [budgetBytes]), so that feeding the same media again (after a
 * rewind, or in another conversation) skips the encoders.
 *
 * - IMAGE of an [ImageInput]: [VisionPreprocessor.image] at [MediaLimits.MAX_IMAGE_TOKENS], one
 *   vision encoder run;
 * - VIDEO of a [VideoInput]: the frames MediaGeometry.videoFrameIndices picks, resized to
 *   MediaGeometry.videoResizeTarget, one vision encoder run per temporal pair (loaded pair by pair,
 *   so that only two frames are in memory at a time), concatenated;
 * - AUDIO of an [AudioInput], or of a [VideoInput]'s soundtrack: the log-mel features and one audio
 *   encoder run.
 *
 * The row counts are those the prompt builder expanded the placeholders to ([expectedRows]); any
 * other count fails loudly. Thread-safe.
 */
internal class MediaFeatures(
    private val visionEncoder: VisionEncoder,
    private val audioEncoder: AudioEncoder,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    private val cache = LinkedHashMap<Key, FloatArray>(16, 0.75f, true)
    private var cachedBytes = 0L

    private data class Key(val mediaKey: String, val stream: FeatureStream)

    @Synchronized
    fun isCached(input: MediaInput, stream: FeatureStream): Boolean = Key(input.key, stream) in cache

    /**
     * The `[rows x HIDDEN_SIZE]` features of [input]'s [stream]. [onRowsEncoded] hears how many
     * rows each encoder run adds (nothing when the features are cached).
     */
    @Synchronized
    fun features(
        input: MediaInput,
        stream: FeatureStream,
        runOptions: OrtSession.RunOptions?,
        onRowsEncoded: (Int) -> Unit,
    ): FloatArray {
        val key = Key(input.key, stream)
        val cached = cache[key]
        if (cached != null) {
            return cached
        }

        val features = encode(input, stream, runOptions, onRowsEncoded)
        val expected = expectedRows(input, stream)
        val rows = features.size / Lfm3Omni.HIDDEN_SIZE
        check(rows == expected) {
            "media ${input.key} gave $rows ${stream.name.lowercase()} feature rows, its placeholders need $expected"
        }
        remember(key, features)
        return features
    }

    @Synchronized
    fun clear() {
        cache.clear()
        cachedBytes = 0
    }

    private fun encode(
        input: MediaInput,
        stream: FeatureStream,
        runOptions: OrtSession.RunOptions?,
        onRowsEncoded: (Int) -> Unit,
    ): FloatArray =
        when {
            input is ImageInput && stream == FeatureStream.IMAGE -> encodeImage(input, runOptions, onRowsEncoded)
            input is VideoInput && stream == FeatureStream.VIDEO -> encodeVideo(input, runOptions, onRowsEncoded)
            input is AudioInput && stream == FeatureStream.AUDIO -> {
                val samples = input.load()
                require(samples.size == input.sampleCount) {
                    "audio ${input.key} declared ${input.sampleCount} samples but loaded ${samples.size}"
                }
                encodeAudio(samples, runOptions, onRowsEncoded)
            }
            input is VideoInput && stream == FeatureStream.AUDIO -> {
                val samples = input.loadSoundtrack()
                require(samples.size == input.soundtrackSamples) {
                    "video ${input.key} declared ${input.soundtrackSamples} soundtrack samples, " +
                        "loaded ${samples.size}"
                }
                encodeAudio(samples, runOptions, onRowsEncoded)
            }
            else -> throw IllegalArgumentException("media ${input.key} has no ${stream.name.lowercase()} features")
        }

    private fun encodeImage(
        input: ImageInput,
        runOptions: OrtSession.RunOptions?,
        onRowsEncoded: (Int) -> Unit,
    ): FloatArray {
        val image = input.load()
        val patches = VisionPreprocessor.image(image, MediaLimits.MAX_IMAGE_TOKENS)
        val features = visionEncoder.encode(patches, runOptions)
        onRowsEncoded(patches.grid.tokens)
        return features
    }

    private fun encodeVideo(
        input: VideoInput,
        runOptions: OrtSession.RunOptions?,
        onRowsEncoded: (Int) -> Unit,
    ): FloatArray {
        val layout = VideoLayout.of(input)
        val features = FloatArray(layout.rows * Lfm3Omni.HIDDEN_SIZE)
        var offset = 0
        for (group in 0 until layout.groups) {
            val first = group * Lfm3Omni.TEMPORAL_PATCH_SIZE
            val end = minOf(first + Lfm3Omni.TEMPORAL_PATCH_SIZE, layout.frameIndices.size)
            val groupIndices = layout.frameIndices.copyOfRange(first, end)
            val frames = input.loadFrames(groupIndices)
            require(frames.size == groupIndices.size) {
                "video ${input.key} loaded ${frames.size} frames for ${groupIndices.size} indices"
            }

            // One or two frames make one temporal pair: a lone last frame is repeated
            val patches = VisionPreprocessor.video(frames, layout.size).single()
            val groupFeatures = visionEncoder.encode(patches, runOptions)
            groupFeatures.copyInto(features, destinationOffset = offset)
            offset += groupFeatures.size
            onRowsEncoded(patches.grid.tokens)
        }
        return features
    }

    private fun encodeAudio(
        samples: FloatArray,
        runOptions: OrtSession.RunOptions?,
        onRowsEncoded: (Int) -> Unit,
    ): FloatArray {
        val features = audioEncoder.encode(samples, runOptions)
        onRowsEncoded(features.size / Lfm3Omni.HIDDEN_SIZE)
        return features
    }

    private fun remember(key: Key, features: FloatArray) {
        cache[key] = features
        cachedBytes += features.size.toLong() * Float.SIZE_BYTES
        val iterator = cache.entries.iterator()
        while (cachedBytes > budgetBytes && cache.size > 1) {
            val eldest = iterator.next()
            cachedBytes -= eldest.value.size.toLong() * Float.SIZE_BYTES
            iterator.remove()
        }
    }

    companion object {
        /** About six large photos or video clips' worth of features. */
        const val DEFAULT_BUDGET_BYTES = 48L * 1024 * 1024

        /**
         * The feature rows the prompt builder gives [input]'s [stream] placeholders.
         *
         * @throws IllegalArgumentException if [input] has no such stream
         */
        fun expectedRows(input: MediaInput, stream: FeatureStream): Int =
            when {
                input is ImageInput && stream == FeatureStream.IMAGE ->
                    MediaGeometry.imageGrid(input.width, input.height, MediaLimits.MAX_IMAGE_TOKENS).tokens
                input is VideoInput && stream == FeatureStream.VIDEO -> VideoLayout.of(input).rows
                input is AudioInput && stream == FeatureStream.AUDIO -> MediaGeometry.audioTokenCount(input.sampleCount)
                input is VideoInput && stream == FeatureStream.AUDIO && input.hasSoundtrack ->
                    MediaGeometry.audioTokenCount(input.soundtrackSamples)
                else -> throw IllegalArgumentException("media ${input.key} has no ${stream.name.lowercase()} features")
            }
    }
}

/** How a video becomes vision tokens: the sampled frames, their size and the temporal pairs. */
private class VideoLayout(
    val frameIndices: IntArray,
    val size: MediaGeometry.Size,
    val groups: Int,
    val rowsPerGroup: Int,
) {
    val rows: Int
        get() = groups * rowsPerGroup

    companion object {
        fun of(input: VideoInput): VideoLayout {
            val frameIndices = MediaGeometry.videoFrameIndices(
                input.frameCount,
                input.fps,
                MediaLimits.MAX_VIDEO_FRAMES,
            )
            val size = MediaGeometry.videoResizeTarget(
                input.width,
                input.height,
                frameIndices.size,
                MediaLimits.MAX_VIDEO_TOKENS,
            )
            val grid = MediaGeometry.Grid(size.height / Lfm3Omni.PATCH_SIZE, size.width / Lfm3Omni.PATCH_SIZE)
            val groups = MediaGeometry.videoGroupCount(frameIndices.size)
            return VideoLayout(frameIndices, size, groups, grid.tokens)
        }
    }
}
