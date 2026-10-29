package com.danemadsen.curie.engine

/** A file of the Hugging Face export, pinned by its size and SHA-256 at [ModelFiles.REVISION]. */
data class RemoteFile(
    val path: String,
    val size: Long,
    val sha256: String,
) {
    /** Every file lands flat in the model folder: the graphs resolve their weights next to them. */
    val fileName: String
        get() = path.substringAfterLast('/')

    val url: String
        get() = "https://huggingface.co/${ModelFiles.REPOSITORY}/resolve/${ModelFiles.REVISION}/$path"
}

/**
 * The q4 CPU bundle of LFM3-Omni-2B-ONNX (decoder_q4, vision_encoder_q8, audio_encoder,
 * codec_decoder and the float32 depth_decoder) and the Curie LoRA assets.
 *
 * The decoder and depth decoder graphs are not downloaded: the APK ships patched copies with LoRA
 * inputs (tools/lora/build-lora-assets.py) that read the downloaded weights.
 */
object ModelFiles {
    const val REPOSITORY = "LiquidAI/LFM3-Omni-2B-ONNX"
    const val REVISION = "7d6bd3fd59a0622e5ce7fefc141145a8bba1435a"

    const val DECODER = "decoder_q4.lora.onnx"
    const val DECODER_ADAPTER = "decoder_q4.onnx_adapter"
    const val DEPTH_DECODER = "depth_decoder.lora.onnx"
    const val DEPTH_DECODER_ADAPTER = "depth_decoder.onnx_adapter"
    const val VISION_ENCODER = "vision_encoder_q8.onnx"
    const val AUDIO_ENCODER = "audio_encoder.onnx"
    const val CODEC_DECODER = "codec_decoder.onnx"
    const val TOKENIZER = "tokenizer.json"

    val DOWNLOADS = listOf(
        RemoteFile(
            "onnx/decoder_q4.onnx_data",
            1_686_859_776,
            "4e0c93e7cf632e7add56e52a63975b5639b8230f5fd73dc67cc05466eb42f6d2",
        ),
        RemoteFile(
            "onnx/depth_decoder.onnx_data",
            335_597_568,
            "47e3eafd341b961c5bca5d3128f178acd028f0e299229e70ffcf05a474fdbf77",
        ),
        RemoteFile(
            "onnx/vision_encoder_q8.onnx",
            126_353,
            "5447beba4d5c66315fc561372d71f7d56c6a201c966639c8f029e53f6c0dd483",
        ),
        RemoteFile(
            "onnx/vision_encoder_q8.onnx_data",
            196_153_344,
            "0ce96611c5c60e6285dfebc915ed261199fff8a123668bee8954b8e1f4ca6e8b",
        ),
        RemoteFile(
            "onnx/audio_encoder.onnx",
            162_507,
            "abde0cdde89473120a23a05c38ec3f2a2dc7bf27b48166d37919eabff7326a3e",
        ),
        RemoteFile(
            "onnx/audio_encoder.onnx_data",
            510_351_360,
            "da44a01a0c788c79a64f431848dd90443211916257447ac4dfb5a2d3d64cd241",
        ),
        RemoteFile(
            "onnx/codec_decoder.onnx",
            94_000,
            "5e1d03189d2169ca0e961d1b468b1625bdc57e082bdae7c332178c8aad719878",
        ),
        RemoteFile(
            "onnx/codec_decoder.onnx_data",
            179_046_408,
            "d0eda417de1c477cc627b730608daa92d43838f3b2865869e74662af7334acfe",
        ),
        RemoteFile(
            "tokenizer.json",
            17_908_634,
            "41927bd314d17d7d7fadb44e10cd0919f7b71528d7741596d8cbd15c6945ec44",
        ),
    )

    /** Shipped in the APK under assets/lora and copied next to the downloads. */
    val BUNDLED = listOf(DECODER, DECODER_ADAPTER, DEPTH_DECODER, DEPTH_DECODER_ADAPTER)

    val DOWNLOAD_SIZE: Long = DOWNLOADS.sumOf { file -> file.size }
}
