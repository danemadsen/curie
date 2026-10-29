package com.danemadsen.curie.engine.media

/**
 * Phone-sized media budgets. The model accepts up to 2,048 tokens per image and 384 video frames;
 * on a phone CPU each vision token costs encoder time and context, so Curie caps them lower. The
 * prompt builder (placeholder counts) and the engine (feature rows) must use the same values.
 */
object MediaLimits {
    /** 256 tokens: about 512 x 512 pixels. */
    const val MAX_IMAGE_TOKENS = 256

    /** At 2 frames per second, 16 seconds before longer clips are sampled more sparsely. */
    const val MAX_VIDEO_FRAMES = 32

    /** Vision tokens over all frames of a clip. */
    const val MAX_VIDEO_TOKENS = 1024
}
