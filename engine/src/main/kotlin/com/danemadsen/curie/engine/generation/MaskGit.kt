package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import java.util.SplittableRandom

/** The depth decoder: the logits of every codebook of a speech frame. */
internal fun interface DepthLogits {
    /**
     * Writes the [Lfm3Omni.NUM_CODEBOOKS] x [Lfm3Omni.CODEBOOK_SIZE] logits (codebook by codebook)
     * for the frame after [hidden], given the [codes] revealed so far and which are still [masked].
     */
    fun compute(hidden: FloatArray, codes: IntArray, masked: BooleanArray, logits: FloatArray)
}

/** The order in which MaskGIT reveals a frame's codebooks: each call, a permutation of 0..11. */
internal fun interface MaskGitOrders {
    fun next(): IntArray

    companion object {
        /** The codebooks in index order: no randomness (the golden tests). */
        val IDENTITY = MaskGitOrders { IntArray(Lfm3Omni.NUM_CODEBOOKS) { codebook -> codebook } }

        /** Uniformly random permutations (Fisher-Yates) from [random]. */
        fun random(random: SplittableRandom) = MaskGitOrders {
            val order = IntArray(Lfm3Omni.NUM_CODEBOOKS) { codebook -> codebook }
            for (last in order.lastIndex downTo 1) {
                val pick = random.nextInt(last + 1)
                val value = order[last]
                order[last] = order[pick]
                order[pick] = value
            }
            order
        }
    }
}

/**
 * liquidonnx's sample_frame (the reference's sample_audio_frame): the 12 codes of one speech frame
 * from the decoder's hidden state. One order per frame splits the codebooks by
 * [Lfm3Omni.MASKGIT_SCHEDULE]; each pass runs the depth decoder on the codes revealed so far and
 * samples the pass's codebooks from their logits ([Sampler], with the repetition penalty over each
 * codebook's codes in the last frames). If any code is the end-of-speech code, the whole frame is.
 *
 * Remembers the frames it returns, except end-of-speech ones, as the repetition history of the
 * frames that follow (the reference's audio_history).
 *
 * @throws IllegalArgumentException when [sampling] cannot sample (see [SamplingSettings], [RecentFrames])
 */
internal class MaskGit(
    private val depth: DepthLogits,
    private val orders: MaskGitOrders,
    private val sampling: AudioSampling,
    private val random: SplittableRandom,
) {
    private val settings = SamplingSettings.of(sampling)
    private val sampler = Sampler(Lfm3Omni.CODEBOOK_SIZE)
    private val logits = FloatArray(Lfm3Omni.NUM_CODEBOOKS * Lfm3Omni.CODEBOOK_SIZE)
    private val scores = FloatArray(Lfm3Omni.CODEBOOK_SIZE)
    private val history = RecentFrames(sampling.repetitionWindow)
    private val codebookHistory = IntArray(history.capacity)

    fun sampleFrame(hidden: FloatArray): IntArray {
        val order = orders.next()
        requirePermutation(order)

        val codes = IntArray(Lfm3Omni.NUM_CODEBOOKS)
        val masked = BooleanArray(Lfm3Omni.NUM_CODEBOOKS) { true }
        var passStart = 0
        for (passSize in Lfm3Omni.MASKGIT_SCHEDULE) {
            val passEnd = passStart + passSize
            depth.compute(hidden, codes, masked, logits)
            for (index in passStart until passEnd) {
                val codebook = order[index]
                codes[codebook] = sampleCode(codebook)
            }
            for (index in passStart until passEnd) {
                masked[order[index]] = false
            }
            passStart = passEnd
        }

        if (codes.any { code -> code == Lfm3Omni.AUDIO_EOS_CODE }) {
            codes.fill(Lfm3Omni.AUDIO_EOS_CODE)
        } else {
            history.add(codes)
        }
        return codes
    }

    private fun sampleCode(codebook: Int): Int {
        val start = codebook * Lfm3Omni.CODEBOOK_SIZE
        logits.copyInto(scores, startIndex = start, endIndex = start + Lfm3Omni.CODEBOOK_SIZE)
        val historySize = history.copyCodebook(codebook, codebookHistory)
        return sampler.sample(scores, settings, codebookHistory, historySize, NOTHING_SUPPRESSED, random)
    }

    private fun requirePermutation(order: IntArray) {
        val seen = BooleanArray(Lfm3Omni.NUM_CODEBOOKS)
        for (codebook in order) {
            require(codebook in seen.indices && !seen[codebook]) {
                "MaskGIT order ${order.contentToString()} is not a permutation of the codebooks"
            }
            seen[codebook] = true
        }
        require(order.size == Lfm3Omni.NUM_CODEBOOKS) {
            "MaskGIT order ${order.contentToString()} is not a permutation of the codebooks"
        }
    }

    private companion object {
        val NOTHING_SUPPRESSED = IntArray(0)
    }
}

/** The last [capacity] speech frames: the per-codebook repetition history of speech sampling. */
internal class RecentFrames(val capacity: Int) {
    // Before the array that the capacity sizes
    init {
        require(capacity >= 0) { "the window must not be negative, got $capacity" }
    }

    private val codes = IntArray(capacity * Lfm3Omni.NUM_CODEBOOKS)
    private var next = 0

    var size = 0
        private set

    fun add(frame: IntArray) {
        require(frame.size == Lfm3Omni.NUM_CODEBOOKS) { "a frame has ${Lfm3Omni.NUM_CODEBOOKS} codes" }
        if (capacity == 0) {
            return
        }
        frame.copyInto(codes, destinationOffset = next * Lfm3Omni.NUM_CODEBOOKS)
        next = (next + 1) % capacity
        if (size < capacity) {
            size++
        }
    }

    /** Copies [codebook]'s code of every frame (in no particular order) into [destination]; returns how many. */
    fun copyCodebook(codebook: Int, destination: IntArray): Int {
        for (frame in 0 until size) {
            destination[frame] = codes[frame * Lfm3Omni.NUM_CODEBOOKS + codebook]
        }
        return size
    }
}
