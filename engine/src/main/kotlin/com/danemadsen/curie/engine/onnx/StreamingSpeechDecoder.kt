package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.SpeechDecoder
import com.danemadsen.curie.engine.media.InverseStft

/**
 * Speech frames to 24 kHz samples as they arrive: codec_decoder (audio_codes `[1, 12, F]` to
 * stft_features `[1, 6F, CODEC_N_FFT + 2]`) and a streamed [InverseStft].
 *
 * The codec is causal, so it decodes the frames of the utterance in chunks: once [chunkFrames]
 * frames are pending (and on [finish]) it runs on them after [contextFrames] frames of left
 * context, and only the STFT rows of the pending frames go on to the inverse STFT. With
 * [Lfm3Omni.CODEC_CONTEXT_FRAMES] of context the samples equal those of decoding the whole
 * utterance at once (the engine's codec test measures it).
 *
 * Use one decoder per stream, from one thread at a time; [session] may be shared. Every codec run
 * happens inside the engine's [guard], so closing the engine waits for it. Once the engine starts
 * closing, [push] and [finish] throw [IllegalStateException], whether or not a codec run is due: a
 * generation listener that pushes frames then makes its generate() throw it, so catch it there or
 * decode on another thread.
 */
internal class StreamingSpeechDecoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val guard: SessionGuard,
    private val chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
    private val contextFrames: Int = Lfm3Omni.CODEC_CONTEXT_FRAMES,
) : SpeechDecoder {
    /** The codes of the kept frames: up to [contextFrames] decoded ones, then the pending ones. */
    private var frames = IntArray(0)
    private var frameCount = 0

    /** Frames at the start of [frames] that are already decoded (the left context). */
    private var decodedFrames = 0

    private var inverseStft = InverseStft()

    init {
        require(chunkFrames > 0) { "chunks must hold at least one frame, got $chunkFrames" }
        require(contextFrames >= 0) { "the context must not be negative, got $contextFrames" }
    }

    override fun push(codes: IntArray): FloatArray {
        require(codes.size == Lfm3Omni.NUM_CODEBOOKS) {
            "a frame has ${Lfm3Omni.NUM_CODEBOOKS} codes, got ${codes.size}"
        }
        for (code in codes) {
            require(code in 0 until Lfm3Omni.AUDIO_EOS_CODE) {
                "speech codes are in 0 until ${Lfm3Omni.AUDIO_EOS_CODE}, got $code " +
                    "(end-of-speech frames are not decoded)"
            }
        }
        guard.checkOpen()

        append(codes)
        val pending = frameCount - decodedFrames
        return if (pending >= chunkFrames) decodePending() else NO_SAMPLES
    }

    override fun finish(): FloatArray {
        guard.checkOpen()

        val pendingSamples = if (frameCount > decodedFrames) decodePending() else NO_SAMPLES
        val remainingSamples = inverseStft.finish()
        frameCount = 0
        decodedFrames = 0
        inverseStft = InverseStft()
        return pendingSamples + remainingSamples
    }

    private fun append(codes: IntArray) {
        val required = (frameCount + 1) * Lfm3Omni.NUM_CODEBOOKS
        if (required > frames.size) {
            val capacity = maxOf(required, frames.size * 2)
            frames = frames.copyOf(capacity)
        }
        codes.copyInto(frames, destinationOffset = frameCount * Lfm3Omni.NUM_CODEBOOKS)
        frameCount++
    }

    /** Decodes the pending frames after their context; returns the samples they complete. */
    private fun decodePending(): FloatArray {
        val stftFeatures = runCodec()
        val rowLength = Lfm3Omni.CODEC_N_FFT + 2
        val firstRow = decodedFrames * Lfm3Omni.CODEC_FRAME_STACK
        val newRows = stftFeatures.copyOfRange(firstRow * rowLength, stftFeatures.size)
        val samples = inverseStft.push(newRows)

        // Keep only the context the next chunk needs
        val dropped = maxOf(0, frameCount - contextFrames)
        frames.copyInto(
            frames,
            destinationOffset = 0,
            startIndex = dropped * Lfm3Omni.NUM_CODEBOOKS,
            endIndex = frameCount * Lfm3Omni.NUM_CODEBOOKS,
        )
        frameCount -= dropped
        decodedFrames = frameCount
        return samples
    }

    /** stft_features of the kept frames: `[frameCount * 6, CODEC_N_FFT + 2]`, row-major. */
    private fun runCodec(): FloatArray {
        // audio_codes is codebook-major: [1, 12, F]
        val codes = LongArray(frameCount * Lfm3Omni.NUM_CODEBOOKS)
        for (frame in 0 until frameCount) {
            for (codebook in 0 until Lfm3Omni.NUM_CODEBOOKS) {
                val code = frames[frame * Lfm3Omni.NUM_CODEBOOKS + codebook]
                codes[codebook * frameCount + frame] = code.toLong()
            }
        }
        val shape = longArrayOf(1, Lfm3Omni.NUM_CODEBOOKS.toLong(), frameCount.toLong())
        val values = guard.whileOpen {
            Tensors.copyOf(environment, codes, *shape).use { audioCodes ->
                val inputs = mapOf(AUDIO_CODES to audioCodes)
                session.run(inputs).use { result ->
                    val stftFeatures = result.get(0) as OnnxTensor
                    Tensors.floatsOf(stftFeatures)
                }
            }
        }
        val expected = frameCount * Lfm3Omni.CODEC_FRAME_STACK * (Lfm3Omni.CODEC_N_FFT + 2)
        check(values.size == expected) { "the codec gave ${values.size} values for $frameCount frames" }
        return values
    }

    companion object {
        /** Frames per codec run while streaming: 0.96 s of speech. */
        const val DEFAULT_CHUNK_FRAMES = 12

        private const val AUDIO_CODES = "audio_codes"
        private val NO_SAMPLES = FloatArray(0)
    }
}
