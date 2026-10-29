package com.danemadsen.curie.engine.prompt

import com.danemadsen.curie.engine.media.RgbImage

/**
 * Media for a prompt. The geometry is known up front (it decides the placeholder count); the pixels
 * and samples are loaded only if the conversation's cache does not already hold the media's
 * features. [key] identifies the content: equal keys must mean equal media.
 */
sealed interface MediaInput {
    val key: String
}

/** A still image whose upright size is [width] x [height]. */
class ImageInput(
    override val key: String,
    val width: Int,
    val height: Int,
    val load: () -> RgbImage,
) : MediaInput

/**
 * A video: [frameCount] frames at [fps], each [width] x [height] upright. [loadFrames] decodes the
 * frames at the given indices (as chosen by MediaGeometry.videoFrameIndices). The soundtrack, if
 * any, has [soundtrackSamples] samples of 16 kHz mono audio.
 */
class VideoInput(
    override val key: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val fps: Double,
    val soundtrackSamples: Int,
    val loadFrames: (IntArray) -> List<RgbImage>,
    val loadSoundtrack: () -> FloatArray,
) : MediaInput {
    val hasSoundtrack: Boolean
        get() = soundtrackSamples > 0
}

/** An audio clip of [sampleCount] samples of 16 kHz mono audio in [-1, 1]. */
class AudioInput(
    override val key: String,
    val sampleCount: Int,
    val load: () -> FloatArray,
) : MediaInput
