package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.generation.DepthLogits

/**
 * depth_decoder for one conversation (batch 1): hidden_states `[1, HIDDEN_SIZE]`, codes `[1, 12]`
 * and masked `[1, 12]` in, logits `[1, 12, CODEBOOK_SIZE]` out. The four tensors live over direct
 * buffers that every pass rewrites, so a MaskGIT pass allocates nothing.
 *
 * [runOptions] carry the depth decoder's LoRA adapter, if any. Not thread-safe; [session] may be
 * shared.
 */
internal class DepthDecoder(
    environment: OrtEnvironment,
    private val session: OrtSession,
    private val runOptions: OrtSession.RunOptions?,
) : DepthLogits, AutoCloseable {
    private val hiddenBuffer = DirectBuffers.floats(Lfm3Omni.HIDDEN_SIZE)
    private val codesBuffer = DirectBuffers.longs(Lfm3Omni.NUM_CODEBOOKS)
    private val maskedBuffer = DirectBuffers.bytes(Lfm3Omni.NUM_CODEBOOKS)
    private val logitsBuffer = DirectBuffers.floats(Lfm3Omni.NUM_CODEBOOKS * Lfm3Omni.CODEBOOK_SIZE)
    private val tensors = ArrayList<OnnxTensor>()
    private val inputs: Map<String, OnnxTensor>
    private val outputs: Map<String, OnnxValue>

    init {
        val codebooks = Lfm3Omni.NUM_CODEBOOKS.toLong()
        val codebookSize = Lfm3Omni.CODEBOOK_SIZE.toLong()
        try {
            val hidden = Tensors.floats(environment, hiddenBuffer, 1, Lfm3Omni.HIDDEN_SIZE.toLong())
            track(hidden)
            val codes = Tensors.longs(environment, codesBuffer, 1, codebooks)
            track(codes)
            val masked = Tensors.booleans(environment, maskedBuffer, 1, codebooks)
            track(masked)
            val logits = Tensors.floats(environment, logitsBuffer, 1, codebooks, codebookSize)
            track(logits)
            inputs = mapOf("hidden_states" to hidden, "codes" to codes, "masked" to masked)
            outputs = mapOf("logits" to logits)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun compute(hidden: FloatArray, codes: IntArray, masked: BooleanArray, logits: FloatArray) {
        DirectBuffers.fill(hiddenBuffer, hidden)
        val codesWriter = codesBuffer.duplicate()
        for (code in codes) {
            codesWriter.put(code.toLong())
        }
        val maskedWriter = maskedBuffer.duplicate()
        for (isMasked in masked) {
            val flag: Byte = if (isMasked) 1 else 0
            maskedWriter.put(flag)
        }

        val result = session.run(inputs, emptySet(), outputs, runOptions)
        // The logits are pinned: the result owns nothing
        result.close()
        DirectBuffers.copy(logitsBuffer, logits)
    }

    override fun close() {
        for (tensor in tensors) {
            tensor.close()
        }
        tensors.clear()
    }

    private fun track(tensor: OnnxTensor) {
        tensors.add(tensor)
    }
}
