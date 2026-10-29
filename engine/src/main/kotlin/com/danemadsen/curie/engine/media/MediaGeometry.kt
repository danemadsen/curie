package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * How media becomes placeholder tokens: the resize targets of Lfm3VlImageProcessor and
 * Lfm3VlVideoProcessor, the video frame sampler and timestamps of Lfm3OmniProcessor, and the token
 * count of the log-mel front end.
 *
 * Python's round() and numpy's round() round half to even, as kotlin.math.round does (Math.round
 * does not); Python's "%.1f" rounds the exact binary value half to even, as BigDecimal does with
 * RoundingMode.HALF_EVEN (String.format does not).
 */
object MediaGeometry {
    data class Size(val width: Int, val height: Int)

    /** The patch grid of a resized image or video frame: [rows] x [columns] patches of 16 pixels. */
    data class Grid(val rows: Int, val columns: Int) {
        val tokens: Int
            get() = rows * columns / (Lfm3Omni.MERGE_SIZE * Lfm3Omni.MERGE_SIZE)
    }

    // === Images ===

    /** Lfm3VlImageProcessor's smart_resize: sides multiples of [factor], pixels within bounds. */
    fun imageResizeTarget(
        width: Int,
        height: Int,
        maxTokens: Int,
        factor: Int = Lfm3Omni.TOKEN_PIXELS,
    ): Size {
        checkAspectRatio(width, height)
        val minPixels = Lfm3Omni.IMAGE_MIN_PIXELS
        val maxPixels = maxTokens * factor * factor

        var resizedHeight = round(height.toDouble() / factor).toInt() * factor
        var resizedWidth = round(width.toDouble() / factor).toInt() * factor
        val area = height.toDouble() * width

        if (resizedHeight.toLong() * resizedWidth > maxPixels) {
            val beta = sqrt(area / maxPixels)
            resizedHeight = max(factor, floor(height / beta / factor).toInt() * factor)
            resizedWidth = max(factor, floor(width / beta / factor).toInt() * factor)
        } else if (resizedHeight.toLong() * resizedWidth < minPixels) {
            val beta = sqrt(minPixels / area)
            resizedHeight = ceil(height * beta / factor).toInt() * factor
            resizedWidth = ceil(width * beta / factor).toInt() * factor
        }
        return Size(resizedWidth, resizedHeight)
    }

    fun imageGrid(width: Int, height: Int, maxTokens: Int): Grid {
        val target = imageResizeTarget(width, height, maxTokens)
        return Grid(target.height / Lfm3Omni.PATCH_SIZE, target.width / Lfm3Omni.PATCH_SIZE)
    }

    // === Videos ===

    /**
     * The sampled frame indices of Lfm3VlVideoProcessor.sample_frames: [Lfm3Omni.VIDEO_FPS] frames
     * per second of the clip, at least [Lfm3Omni.VIDEO_MIN_FRAMES] and at most [maxFrames] (and
     * the clip's frame count), spread as np.linspace(0, frameCount - 1, n).round().
     */
    fun videoFrameIndices(frameCount: Int, fps: Double, maxFrames: Int): IntArray {
        require(frameCount > 0) { "a video needs frames" }
        require(fps > 0.0 && fps.isFinite()) { "the video's frame rate must be positive, got $fps" }

        val byRate = (frameCount / fps * Lfm3Omni.VIDEO_FPS).toInt()
        val count = minOf(max(byRate, Lfm3Omni.VIDEO_MIN_FRAMES), maxFrames, frameCount)
        if (count == 1) {
            return intArrayOf(0)
        }

        // numpy.linspace: i * step, with the last value set to the stop exactly
        val stop = (frameCount - 1).toDouble()
        val step = stop / (count - 1)
        return IntArray(count) { index ->
            val value = if (index == count - 1) stop else index * step
            round(value).toInt()
        }
    }

    /** Frames grouped in temporal pairs: an odd count repeats the last frame. */
    fun videoGroupCount(sampledFrames: Int): Int =
        (sampledFrames + Lfm3Omni.TEMPORAL_PATCH_SIZE - 1) / Lfm3Omni.TEMPORAL_PATCH_SIZE

    /**
     * Lfm3VlVideoProcessor's smart_resize for [sampledFrames] frames of [width] x [height] within a
     * budget of [maxTokens] tokens over the whole clip.
     */
    fun videoResizeTarget(
        width: Int,
        height: Int,
        sampledFrames: Int,
        maxTokens: Int,
        factor: Int = Lfm3Omni.TOKEN_PIXELS,
    ): Size {
        val temporalFactor = Lfm3Omni.TEMPORAL_PATCH_SIZE
        require(sampledFrames >= temporalFactor) { "a video needs at least $temporalFactor sampled frames" }

        var scaledHeight = height.toDouble()
        var scaledWidth = width.toDouble()
        if (height < factor || width < factor) {
            val scale = max(factor.toDouble() / height, factor.toDouble() / width)
            scaledHeight = floor(height * scale)
            scaledWidth = floor(width * scale)
        }
        checkAspectRatio(scaledWidth.toInt(), scaledHeight.toInt())

        // tokens = (frames / temporal) * height * width / factor^2
        val minPixels = Lfm3Omni.VIDEO_MIN_PIXELS.toDouble()
        val maxPixels = maxTokens.toDouble() * temporalFactor * factor * factor
        var resizedHeight = round(scaledHeight / factor).toInt() * factor
        var resizedWidth = round(scaledWidth / factor).toInt() * factor
        val resizedFrames = round(sampledFrames.toDouble() / temporalFactor).toInt() * temporalFactor
        val volume = sampledFrames * scaledHeight * scaledWidth
        val resizedVolume = resizedFrames.toDouble() * resizedHeight * resizedWidth

        if (resizedVolume > maxPixels) {
            val beta = sqrt(volume / maxPixels)
            resizedHeight = max(factor, floor(scaledHeight / beta / factor).toInt() * factor)
            resizedWidth = max(factor, floor(scaledWidth / beta / factor).toInt() * factor)
        } else if (resizedVolume < minPixels) {
            val beta = sqrt(minPixels / volume)
            resizedHeight = ceil(scaledHeight * beta / factor).toInt() * factor
            resizedWidth = ceil(scaledWidth * beta / factor).toInt() * factor
        }
        return Size(resizedWidth, resizedHeight)
    }

    /**
     * Lfm3OmniProcessor._calculate_timestamps: the mean time of each temporal pair, in seconds, of
     * frames at [fps] (rounded to 6 decimals first, as the processor does).
     */
    fun videoGroupTimestamps(frameIndices: IntArray, fps: Double): DoubleArray {
        val roundedFps = roundHalfEven(fps, 6)
        val pairs = videoGroupCount(frameIndices.size)
        return DoubleArray(pairs) { group ->
            val first = frameIndices[group * 2]
            val second = frameIndices[min(group * 2 + 1, frameIndices.size - 1)]
            (first + second) / (2 * roundedFps)
        }
    }

    /** The "<0.5 seconds>" text that precedes a frame group, formatted as Python's f"{x:.1f}". */
    fun timestampText(seconds: Double): String {
        val formatted = BigDecimal(seconds).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
        return "<$formatted seconds>"
    }

    /**
     * How a soundtrack's [soundtrackTokens] encoder tokens split over the frame groups
     * (Lfm3OmniProcessor._video_expansion with use_audio_in_video): group g takes the tokens from
     * bounds[g] until bounds[g + 1].
     */
    fun soundtrackBounds(frameIndices: IntArray, fps: Double, soundtrackTokens: Int): IntArray {
        val roundedFps = roundHalfEven(fps, 6)
        val groups = videoGroupCount(frameIndices.size)
        val secondsPerToken =
            Lfm3Omni.MEL_HOP_LENGTH.toDouble() * Lfm3Omni.ENCODER_STRIDE / Lfm3Omni.INPUT_SAMPLE_RATE

        val bounds = IntArray(groups + 1)
        for (group in 1 until groups) {
            val start = frameIndices[group * Lfm3Omni.TEMPORAL_PATCH_SIZE] / roundedFps
            bounds[group] = min(soundtrackTokens, round(start / secondsPerToken).toInt())
        }
        bounds[groups] = soundtrackTokens

        // A running maximum keeps the bounds non-decreasing
        for (index in 1..groups) {
            bounds[index] = max(bounds[index], bounds[index - 1])
        }
        return bounds
    }

    // === Audio ===

    /** Mel frames of a clip of [samples] samples: right-padded to whole hops, at least one window. */
    fun melFrameCount(samples: Int): Int {
        val nFft = Lfm3Omni.MEL_N_FFT
        val hop = Lfm3Omni.MEL_HOP_LENGTH
        val padded = max(nFft, samples + Math.floorMod(nFft - samples, hop))
        return 1 + (padded - nFft) / hop
    }

    /** Audio encoder tokens of a clip: one per [Lfm3Omni.ENCODER_STRIDE] mel frames, rounded up. */
    fun audioTokenCount(samples: Int): Int {
        val frames = melFrameCount(samples)
        return (frames + Lfm3Omni.ENCODER_STRIDE - 1) / Lfm3Omni.ENCODER_STRIDE
    }

    private fun checkAspectRatio(width: Int, height: Int) {
        val longer = max(width, height).toDouble()
        val shorter = min(width, height).toDouble()
        require(shorter > 0 && longer / shorter <= 200) { "aspect ratio must be at most 200, got ${width}x$height" }
    }

    private fun roundHalfEven(value: Double, decimals: Int): Double =
        BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toDouble()
}
