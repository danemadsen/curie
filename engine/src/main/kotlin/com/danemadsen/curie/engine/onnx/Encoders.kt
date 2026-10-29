package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.media.LogMelSpectrogram
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.VisionPatches

/**
 * vision_encoder_q8: the pixel_values of one image or temporal pair of video frames
 * (`[rows / 2, columns / 2, 2, 2, PATCH_DIM]`) in, one feature row per 2 x 2 patches out.
 */
internal class VisionEncoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) {
    /** The `[grid.tokens x HIDDEN_SIZE]` features of [patches], row-major. */
    fun encode(patches: VisionPatches, runOptions: OrtSession.RunOptions?): FloatArray {
        val expectedRows = patches.grid.tokens
        val features = Tensors.copyOf(environment, patches.pixelValues, *patches.shape).use { pixelValues ->
            val inputs = mapOf(PIXEL_VALUES to pixelValues)
            runSingleOutput(session, inputs, runOptions)
        }
        checkRows(features, expectedRows, "the vision encoder")
        return features
    }

    private companion object {
        const val PIXEL_VALUES = "pixel_values"
    }
}

/**
 * audio_encoder: the log-mel features of a 16 kHz clip ([LogMelSpectrogram], `[1, 128, T]`) in,
 * one feature row per [Lfm3Omni.ENCODER_STRIDE] mel frames out.
 */
internal class AudioEncoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) {
    /** The `[audioTokenCount x HIDDEN_SIZE]` features of [samples], row-major. */
    fun encode(samples: FloatArray, runOptions: OrtSession.RunOptions?): FloatArray {
        val melFrames = MediaGeometry.melFrameCount(samples.size)
        val melFeatures = LogMelSpectrogram.compute(samples)
        val shape = longArrayOf(1, Lfm3Omni.MEL_BINS.toLong(), melFrames.toLong())
        val features = Tensors.copyOf(environment, melFeatures, *shape).use { inputFeatures ->
            val inputs = mapOf(INPUT_FEATURES to inputFeatures)
            runSingleOutput(session, inputs, runOptions)
        }
        val expectedRows = MediaGeometry.audioTokenCount(samples.size)
        checkRows(features, expectedRows, "the audio encoder")
        return features
    }

    private companion object {
        const val INPUT_FEATURES = "input_features"
    }
}

/** Runs a graph with one output and returns that output's values. */
private fun runSingleOutput(
    session: OrtSession,
    inputs: Map<String, OnnxTensor>,
    runOptions: OrtSession.RunOptions?,
): FloatArray {
    val result = session.run(inputs, runOptions)
    return result.use { outputs ->
        val tensor = outputs.get(0) as OnnxTensor
        Tensors.floatsOf(tensor)
    }
}

private fun checkRows(features: FloatArray, expectedRows: Int, encoder: String) {
    val rows = features.size / Lfm3Omni.HIDDEN_SIZE
    check(rows == expectedRows && features.size % Lfm3Omni.HIDDEN_SIZE == 0) {
        "$encoder gave $rows feature rows where $expectedRows were expected"
    }
}
