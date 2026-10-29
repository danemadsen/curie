package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.CacheMemory
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.MemoryBlock
import java.nio.Buffer
import java.nio.ByteOrder

/**
 * The inputs of one decoder run besides the cache: [tokens] and, for their placeholders in order,
 * the feature rows (`[rows x HIDDEN_SIZE]`, row-major) and the speech frames' codes (12 per
 * <|audio_out|>).
 */
internal class DecoderFeed(
    val tokens: IntArray,
    val imageFeatures: FloatArray = NO_FEATURES,
    val videoFeatures: FloatArray = NO_FEATURES,
    val audioFeatures: FloatArray = NO_FEATURES,
    val audioOutCodes: IntArray = NO_CODES,
) {
    init {
        require(tokens.isNotEmpty()) { "a decoder run needs at least one token" }
        for (features in listOf(imageFeatures, videoFeatures, audioFeatures)) {
            require(features.size % Lfm3Omni.HIDDEN_SIZE == 0) { "feature rows have ${Lfm3Omni.HIDDEN_SIZE} values" }
        }
        require(audioOutCodes.size % Lfm3Omni.NUM_CODEBOOKS == 0) { "frames have ${Lfm3Omni.NUM_CODEBOOKS} codes" }
    }

    companion object {
        val NO_FEATURES = FloatArray(0)
        val NO_CODES = IntArray(0)
    }
}

/**
 * One conversation's decoder_q4 cache, run in place the way liquidonnx's CachedDecoder runs it
 * with a capacity.
 *
 * Each attention layer's key and value cache is one `[1, KV_HEADS, capacity, HEAD_DIM]` tensor fed
 * as past_key_values.N.* and pinned as the output present.N.*: GroupQueryAttention sees the shared
 * buffer and appends in place, reading the [length] valid positions that attention_mask
 * (`[1, length + S]` ones) announces. Each convolution layer's state is double-buffered: a run reads
 * one buffer and writes the other, and the two swap only after the run succeeded, so a failed or
 * terminated run leaves the cache as it was. The logits and hidden state of the last position
 * (logits_to_keep = 1) land in pinned buffers too: a decode step allocates no large memory.
 *
 * KV entries at and after [length] are free: rewinding is setting [length] and restoring the
 * convolution state of that position. All key and value caches share one block of [cacheMemory].
 *
 * Not thread-safe: one run at a time. [session] may be shared with other caches.
 */
internal class DecoderSession(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val capacity: Int,
    cacheMemory: CacheMemory,
) : AutoCloseable {
    /** Everything to close with the cache. */
    private val owned = ArrayList<AutoCloseable>()

    private val keyCaches = ArrayList<OnnxTensor>()
    private val valueCaches = ArrayList<OnnxTensor>()
    private val convStates: ConvStates
    private val logitsBuffer = DirectBuffers.floats(Lfm3Omni.VOCAB_SIZE)
    private val hiddenBuffer = DirectBuffers.floats(Lfm3Omni.HIDDEN_SIZE)
    private val logits: OnnxTensor
    private val hidden: OnnxTensor
    private val logitsToKeep: OnnxTensor
    private val noFeatures: OnnxTensor
    private val noCodes: OnnxTensor

    /** [capacity] ones: every attention_mask is a prefix of them. */
    private val ones = DirectBuffers.longs(capacity)

    /** Positions in the cache. */
    @Volatile
    var length = 0
        private set

    /** Whether the logits and hidden state belong to the last position of the cache. */
    var outputsValid = false
        private set

    init {
        require(capacity > 0) { "the capacity must be positive, got $capacity" }
        val onesWriter = ones.duplicate()
        repeat(capacity) { onesWriter.put(1L) }

        try {
            // Owned before the tensors over it, so it is closed after them
            val cacheCount = 2 * Lfm3Omni.ATTENTION_LAYERS.size
            val blockBytes = Math.multiplyExact(kvCacheBytes, cacheCount)
            val kvBlock = own(cacheMemory.allocate(blockBytes))
            require(kvBlock.buffer.isDirect) { "the KV cache memory must be a direct buffer" }
            require(kvBlock.buffer.capacity() >= blockBytes) { "the KV cache memory holds fewer than $blockBytes bytes" }

            for (layer in Lfm3Omni.ATTENTION_LAYERS.indices) {
                keyCaches.add(newKvCache(kvBlock, 2 * layer))
                valueCaches.add(newKvCache(kvBlock, 2 * layer + 1))
            }
            convStates = own(ConvStates(environment))

            val logitsTensor = Tensors.floats(environment, logitsBuffer, 1, 1, Lfm3Omni.VOCAB_SIZE.toLong())
            logits = own(logitsTensor)
            val hiddenTensor = Tensors.floats(environment, hiddenBuffer, 1, 1, Lfm3Omni.HIDDEN_SIZE.toLong())
            hidden = own(hiddenTensor)
            val keepOne = longArrayOf(1)
            val keepOneTensor = Tensors.copyOf(environment, keepOne, 1)
            logitsToKeep = own(keepOneTensor)

            // Runs without media or speech frames feed empty [0, HIDDEN_SIZE] and [0, 12] inputs
            val emptyFeatures = DirectBuffers.floats(0)
            val emptyFeaturesTensor = Tensors.floats(environment, emptyFeatures, 0, Lfm3Omni.HIDDEN_SIZE.toLong())
            noFeatures = own(emptyFeaturesTensor)
            val emptyCodes = DirectBuffers.longs(0)
            val emptyCodesTensor = Tensors.longs(environment, emptyCodes, 0, Lfm3Omni.NUM_CODEBOOKS.toLong())
            noCodes = own(emptyCodesTensor)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    val hasRoom: Boolean
        get() = length < capacity

    /**
     * Feeds [feed] after the cached positions. On success the cache grows by its tokens and the
     * outputs follow its last one; when the run fails (or is terminated) it throws, the cache is
     * unchanged and the outputs are invalid.
     */
    fun run(feed: DecoderFeed, runOptions: OrtSession.RunOptions?) {
        val count = feed.tokens.size
        val total = length + count
        check(total <= capacity) { "$total positions exceed the capacity $capacity" }

        val runTensors = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs[INPUT_IDS] = track(runTensors, idsTensor(feed.tokens))
            inputs[POSITION_IDS] = track(runTensors, positionsTensor(count))
            inputs[ATTENTION_MASK] = track(runTensors, attentionMask(total))
            inputs[LOGITS_TO_KEEP] = logitsToKeep
            inputs[IMAGE_FEATURES] = featureTensor(feed.imageFeatures, runTensors)
            inputs[VIDEO_FEATURES] = featureTensor(feed.videoFeatures, runTensors)
            inputs[AUDIO_FEATURES] = featureTensor(feed.audioFeatures, runTensors)
            inputs[AUDIO_OUT_CODES] = codesTensor(feed.audioOutCodes, runTensors)

            val outputs = HashMap<String, OnnxValue>()
            outputs[LOGITS] = logits
            outputs[HIDDEN_STATES] = hidden
            for ((index, layer) in Lfm3Omni.CONV_LAYERS.withIndex()) {
                inputs["past_conv.$layer"] = convStates.current(index)
                outputs["present_conv.$layer"] = convStates.next(index)
            }
            for ((index, layer) in Lfm3Omni.ATTENTION_LAYERS.withIndex()) {
                inputs["past_key_values.$layer.key"] = keyCaches[index]
                inputs["past_key_values.$layer.value"] = valueCaches[index]
                outputs["present.$layer.key"] = keyCaches[index]
                outputs["present.$layer.value"] = valueCaches[index]
            }

            outputsValid = false
            val result = session.run(inputs, emptySet(), outputs, runOptions)
            // Every output is pinned: the result owns none of them
            result.close()
        } finally {
            for (tensor in runTensors) {
                tensor.close()
            }
        }
        convStates.swap()
        length = total
        outputsValid = true
    }

    fun copyLogits(destination: FloatArray) {
        check(outputsValid) { "no logits: the last run did not complete" }
        DirectBuffers.copy(logitsBuffer, destination)
    }

    fun copyHidden(destination: FloatArray) {
        check(outputsValid) { "no hidden state: the last run did not complete" }
        DirectBuffers.copy(hiddenBuffer, destination)
    }

    /** A copy of the convolution states at the current length, with the outputs when [withOutputs]. */
    fun snapshot(withOutputs: Boolean): CacheSnapshot {
        val snapshot = CacheSnapshot(length, convStates.copy())
        if (withOutputs && outputsValid) {
            val savedLogits = FloatArray(Lfm3Omni.VOCAB_SIZE)
            val savedHidden = FloatArray(Lfm3Omni.HIDDEN_SIZE)
            copyLogits(savedLogits)
            copyHidden(savedHidden)
            snapshot.outputs = DecoderOutputs(savedLogits, savedHidden)
        }
        return snapshot
    }

    /** Rewinds to [snapshot]; its saved outputs, if any, become the current ones. */
    fun restore(snapshot: CacheSnapshot) {
        check(snapshot.position <= length) { "cannot rewind from $length forward to ${snapshot.position}" }
        convStates.restore(snapshot.convStates)
        length = snapshot.position
        val saved = snapshot.outputs
        if (saved != null) {
            DirectBuffers.fill(logitsBuffer, saved.logits)
            DirectBuffers.fill(hiddenBuffer, saved.hidden)
        }
        outputsValid = saved != null
    }

    /** Empties the cache: zero convolution states, no positions. */
    fun reset() {
        convStates.zero()
        length = 0
        outputsValid = false
    }

    override fun close() {
        for (resource in owned.asReversed()) {
            resource.close()
        }
        owned.clear()
    }

    private fun <T : AutoCloseable> own(resource: T): T {
        owned.add(resource)
        return resource
    }

    /** The bytes of one layer's key (or value) cache. */
    private val kvCacheBytes: Int
        get() {
            val values = Math.multiplyExact(Lfm3Omni.KV_HEADS * Lfm3Omni.HEAD_DIM, capacity)
            return Math.multiplyExact(values, Float.SIZE_BYTES)
        }

    /** The [index]th cache of [block]: its own slice of the block, read as native-order floats. */
    private fun newKvCache(block: MemoryBlock, index: Int): OnnxTensor {
        val bytes = kvCacheBytes
        val start = index * bytes
        val region = block.buffer.duplicate()
        // Through Buffer: the covariant ByteBuffer.position and limit are newer than Android's runtime
        (region as Buffer).position(start)
        (region as Buffer).limit(start + bytes)
        val slice = region.slice()
        slice.order(ByteOrder.nativeOrder())
        val values = slice.asFloatBuffer()

        val tensor = Tensors.floats(
            environment,
            values,
            1,
            Lfm3Omni.KV_HEADS.toLong(),
            capacity.toLong(),
            Lfm3Omni.HEAD_DIM.toLong(),
        )
        return own(tensor)
    }

    private fun idsTensor(tokens: IntArray): OnnxTensor {
        val ids = LongArray(tokens.size) { index -> tokens[index].toLong() }
        return Tensors.copyOf(environment, ids, 1, tokens.size.toLong())
    }

    private fun positionsTensor(count: Int): OnnxTensor {
        val positions = LongArray(count) { index -> (length + index).toLong() }
        return Tensors.copyOf(environment, positions, 1, count.toLong())
    }

    private fun attentionMask(total: Int): OnnxTensor {
        val mask = DirectBuffers.prefix(ones, total)
        return Tensors.longs(environment, mask, 1, total.toLong())
    }

    private fun featureTensor(features: FloatArray, runTensors: MutableList<OnnxTensor>): OnnxTensor {
        if (features.isEmpty()) {
            return noFeatures
        }
        val rows = features.size / Lfm3Omni.HIDDEN_SIZE
        val tensor = Tensors.copyOf(environment, features, rows.toLong(), Lfm3Omni.HIDDEN_SIZE.toLong())
        return track(runTensors, tensor)
    }

    private fun codesTensor(codes: IntArray, runTensors: MutableList<OnnxTensor>): OnnxTensor {
        if (codes.isEmpty()) {
            return noCodes
        }
        val frames = codes.size / Lfm3Omni.NUM_CODEBOOKS
        val values = LongArray(codes.size) { index -> codes[index].toLong() }
        val tensor = Tensors.copyOf(environment, values, frames.toLong(), Lfm3Omni.NUM_CODEBOOKS.toLong())
        return track(runTensors, tensor)
    }

    private fun track(runTensors: MutableList<OnnxTensor>, tensor: OnnxTensor): OnnxTensor {
        runTensors.add(tensor)
        return tensor
    }

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val POSITION_IDS = "position_ids"
        const val IMAGE_FEATURES = "image_features"
        const val VIDEO_FEATURES = "video_features"
        const val AUDIO_FEATURES = "audio_features"
        const val AUDIO_OUT_CODES = "audio_out_codes"
        const val LOGITS_TO_KEEP = "logits_to_keep"
        const val LOGITS = "logits"
        const val HIDDEN_STATES = "hidden_states"
    }
}

/**
 * The convolution states of every conv layer, double-buffered: runs read [current] and write
 * [next], and [swap] makes the written ones current.
 */
private class ConvStates(environment: OrtEnvironment) : AutoCloseable {
    private val layerCount = Lfm3Omni.CONV_LAYERS.size
    private val layerValues = Lfm3Omni.HIDDEN_SIZE * Lfm3Omni.CONV_CACHE_LENGTH
    private val buffers = Array(2) { Array(layerCount) { DirectBuffers.floats(layerValues) } }
    private val tensors = ArrayList<OnnxTensor>()
    private var currentSet = 0

    init {
        try {
            for (set in buffers) {
                for (buffer in set) {
                    val tensor = Tensors.floats(
                        environment,
                        buffer,
                        1,
                        Lfm3Omni.HIDDEN_SIZE.toLong(),
                        Lfm3Omni.CONV_CACHE_LENGTH.toLong(),
                    )
                    tensors.add(tensor)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun current(layerIndex: Int): OnnxTensor = tensors[currentSet * layerCount + layerIndex]

    fun next(layerIndex: Int): OnnxTensor = tensors[(1 - currentSet) * layerCount + layerIndex]

    fun swap() {
        currentSet = 1 - currentSet
    }

    /** The current states of all layers, one after the other. */
    fun copy(): FloatArray {
        val values = FloatArray(layerCount * layerValues)
        for ((index, buffer) in buffers[currentSet].withIndex()) {
            val reader = buffer.duplicate()
            reader.get(values, index * layerValues, layerValues)
        }
        return values
    }

    fun restore(values: FloatArray) {
        require(values.size == layerCount * layerValues) { "expected ${layerCount * layerValues} state values" }
        for ((index, buffer) in buffers[currentSet].withIndex()) {
            val writer = buffer.duplicate()
            writer.put(values, index * layerValues, layerValues)
        }
    }

    fun zero() {
        val zeros = FloatArray(layerValues)
        for (buffer in buffers[currentSet]) {
            DirectBuffers.fill(buffer, zeros)
        }
    }

    override fun close() {
        for (tensor in tensors) {
            tensor.close()
        }
        tensors.clear()
    }
}
