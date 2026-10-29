package com.danemadsen.curie.engine

/**
 * Fixed facts about LFM3-Omni-2B, from the export's config.json, processor_config.json and
 * audio_tokenizer/config.json at [ModelFiles.REVISION]. The model settings are baked in: the app
 * exposes none of them.
 */
object Lfm3Omni {
    const val HIDDEN_SIZE = 2048
    const val VOCAB_SIZE = 128_000

    // === Special tokens ===

    const val PAD_TOKEN = 124_893
    const val BOS_TOKEN = 124_894
    const val IM_START_TOKEN = 124_899
    const val IM_END_TOKEN = 124_900
    const val VISION_START_TOKEN = 125_009
    const val VISION_END_TOKEN = 125_010
    const val AUDIO_START_TOKEN = 125_012
    const val TEXT_END_TOKEN = 125_014
    const val MIXED_START_TOKEN = 125_015
    const val VIDEO_TOKEN = 125_029
    const val IMAGE_TOKEN = 125_030
    const val AUDIO_TOKEN = 125_031
    const val AUDIO_OUT_TOKEN = 125_032

    /** Placeholders filled with features: never sampled as text. */
    val PLACEHOLDER_TOKENS = intArrayOf(VIDEO_TOKEN, IMAGE_TOKEN, AUDIO_TOKEN, AUDIO_OUT_TOKEN)

    // === Decoder (decoder_q4) ===

    const val NUM_LAYERS = 24
    val ATTENTION_LAYERS = intArrayOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)
    val CONV_LAYERS = intArrayOf(0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23)
    const val KV_HEADS = 4
    const val HEAD_DIM = 128
    const val CONV_CACHE_LENGTH = 7

    // === Speech frames (depth decoder) ===

    const val NUM_CODEBOOKS = 12
    const val CODEBOOK_SIZE = 2017
    const val AUDIO_EOS_CODE = 2016

    /** Codebooks revealed per depth decoder pass (MaskGIT). */
    val MASKGIT_SCHEDULE = intArrayOf(4, 4, 4)

    /** Interleaved answers alternate this many text tokens and speech frames. */
    const val INTERLEAVED_TEXT_SLOTS = 6
    const val INTERLEAVED_AUDIO_SLOTS = 12

    // === Speech out (codec decoder + inverse STFT) ===

    const val OUTPUT_SAMPLE_RATE = 24_000
    const val CODEC_FRAME_STACK = 6
    const val CODEC_N_FFT = 1280
    const val CODEC_HOP_LENGTH = 320

    /** Output samples per speech frame (80 ms). */
    const val FRAME_SAMPLES = CODEC_HOP_LENGTH * CODEC_FRAME_STACK

    /**
     * Speech frames of left context that make a chunk of the causal codec decode exactly as the
     * whole utterance does: 5 convolutions of width 7 and 3 attentions over 30 STFT frames span
     * 5 * 6 + 3 * 29 = 117 STFT frames, under 20 speech frames.
     */
    const val CODEC_CONTEXT_FRAMES = 20

    // === Speech in (log-mel front end + audio encoder) ===

    const val INPUT_SAMPLE_RATE = 16_000
    const val MEL_BINS = 128
    const val MEL_N_FFT = 400
    const val MEL_WIN_LENGTH = 400
    const val MEL_HOP_LENGTH = 160
    const val MEL_LOG_FLOOR = -12.0
    const val MEL_NORMALIZATION_OFFSET = 4.0
    const val MEL_NORMALIZATION_DIVISOR = 4.0

    /** Mel frames per audio encoder token. */
    const val ENCODER_STRIDE = 8

    // === Vision (image and video preprocessing + vision encoder) ===

    const val PATCH_SIZE = 16
    const val MERGE_SIZE = 2
    const val TEMPORAL_PATCH_SIZE = 2

    /** Pixel values per patch row: 3 channels x 2 temporal x 16 x 16. */
    const val PATCH_DIM = 3 * TEMPORAL_PATCH_SIZE * PATCH_SIZE * PATCH_SIZE

    /** Side of the pixel block that becomes one language token. */
    const val TOKEN_PIXELS = PATCH_SIZE * MERGE_SIZE

    const val IMAGE_MIN_PIXELS = 65_536
    const val VIDEO_MIN_PIXELS = 4_096
    const val VIDEO_FPS = 2.0
    const val VIDEO_MIN_FRAMES = 4

    /** image_mean = image_std = 0.5 at 1 / 255 scale, fused as torchvision does: (v - PIXEL_CENTER) / PIXEL_CENTER. */
    const val PIXEL_CENTER = 127.5f
}
