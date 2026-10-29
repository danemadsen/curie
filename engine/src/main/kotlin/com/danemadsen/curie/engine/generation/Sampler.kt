package com.danemadsen.curie.engine.generation

import java.util.SplittableRandom
import kotlin.math.exp

/**
 * The settings of one sample_token call, shared by text tokens and speech codes. A temperature of
 * 0 or less samples greedily; a top k of 0 keeps every id.
 */
internal data class SamplingSettings(
    val temperature: Float,
    val topK: Int,
    val repetitionPenalty: Float,
) {
    init {
        require(temperature.isFinite()) { "the temperature must be a finite number, got $temperature" }
        require(topK >= 0) { "topK must not be negative, got $topK" }
        require(repetitionPenalty > 0f) { "the repetition penalty must be positive, got $repetitionPenalty" }
    }

    companion object {
        fun of(sampling: TextSampling) =
            SamplingSettings(sampling.temperature, sampling.topK, sampling.repetitionPenalty)

        fun of(sampling: AudioSampling) =
            SamplingSettings(sampling.temperature, sampling.topK, sampling.repetitionPenalty)
    }
}

/**
 * liquidonnx's sample_token (Lfm3AudioForConditionalGeneration._sample) over one row of scores:
 *
 * 1. the repetition penalty of transformers' RepetitionPenaltyLogitsProcessor over the history, once
 *    per distinct id: a negative score is multiplied by the penalty, any other divided by it;
 * 2. the suppressed ids set to -infinity;
 * 3. the argmax (the first one) when the temperature is 0 or less;
 * 4. otherwise the scores divided by the temperature, all but the top k (ties with the k-th kept)
 *    set to -infinity, and a draw from their softmax, computed in double.
 *
 * The arithmetic on the scores is float32, as numpy's on the reference's float32 logits. The draw
 * comes from a [SplittableRandom], so sampled ids are reproducible from a seed but differ from
 * numpy's generator; greedy ids match the reference exactly.
 *
 * Holds scratch arrays of [vocabularySize] values: use an instance from one thread at a time.
 */
internal class Sampler(private val vocabularySize: Int) {
    private var probabilities = DoubleArray(0)
    private var topScores = FloatArray(0)

    /**
     * The id picked from [scores], which it overwrites with the penalized and warped scores.
     * [history] holds the ids to penalize in its first [historySize] values (in any order).
     */
    fun sample(
        scores: FloatArray,
        settings: SamplingSettings,
        history: IntArray,
        historySize: Int,
        suppressed: IntArray,
        random: SplittableRandom,
    ): Int {
        require(scores.size == vocabularySize) { "expected $vocabularySize scores, got ${scores.size}" }
        applyRepetitionPenalty(scores, settings.repetitionPenalty, history, historySize)
        for (id in suppressed) {
            scores[id] = Float.NEGATIVE_INFINITY
        }

        if (settings.temperature <= 0f) {
            return argmax(scores)
        }
        for (id in scores.indices) {
            scores[id] = scores[id] / settings.temperature
        }
        if (settings.topK in 1 until vocabularySize) {
            keepTopK(scores, settings.topK)
        }
        return draw(scores, random)
    }

    private fun applyRepetitionPenalty(scores: FloatArray, penalty: Float, history: IntArray, historySize: Int) {
        if (penalty == 1f) {
            return
        }
        for (index in 0 until historySize) {
            val id = history[index]
            if (occursBefore(history, index, id)) {
                continue
            }
            val score = scores[id]
            scores[id] = if (score < 0f) score * penalty else score / penalty
        }
    }

    private fun occursBefore(history: IntArray, index: Int, id: Int): Boolean {
        for (earlier in 0 until index) {
            if (history[earlier] == id) {
                return true
            }
        }
        return false
    }

    /** Sets every score below the k-th largest (counting repeated values) to -infinity. */
    private fun keepTopK(scores: FloatArray, k: Int) {
        val threshold = kthLargest(scores, k)
        for (id in scores.indices) {
            if (scores[id] < threshold) {
                scores[id] = Float.NEGATIVE_INFINITY
            }
        }
    }

    /** np.partition(scores, -k)[-k], with a min-heap of the k largest values. */
    private fun kthLargest(scores: FloatArray, k: Int): Float {
        if (topScores.size < k) {
            topScores = FloatArray(k)
        }
        val heap = topScores
        for (index in 0 until k) {
            heap[index] = scores[index]
            siftUp(heap, index)
        }
        for (index in k until scores.size) {
            val score = scores[index]
            if (score > heap[0]) {
                heap[0] = score
                siftDown(heap, k)
            }
        }
        return heap[0]
    }

    private fun siftUp(heap: FloatArray, start: Int) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heap[parent] <= heap[child]) {
                return
            }
            swap(heap, parent, child)
            child = parent
        }
    }

    private fun siftDown(heap: FloatArray, size: Int) {
        var parent = 0
        while (true) {
            val left = 2 * parent + 1
            if (left >= size) {
                return
            }
            val right = left + 1
            val smaller = if (right < size && heap[right] < heap[left]) right else left
            if (heap[parent] <= heap[smaller]) {
                return
            }
            swap(heap, parent, smaller)
            parent = smaller
        }
    }

    private fun swap(values: FloatArray, first: Int, second: Int) {
        val value = values[first]
        values[first] = values[second]
        values[second] = value
    }

    /** A draw from the softmax of [scores] in double (rng.choice with p = softmax(scores)). */
    private fun draw(scores: FloatArray, random: SplittableRandom): Int {
        if (probabilities.size < scores.size) {
            probabilities = DoubleArray(scores.size)
        }
        val maximum = scores[argmax(scores)].toDouble()
        var total = 0.0
        for (id in scores.indices) {
            val weight = exp(scores[id] - maximum)
            probabilities[id] = weight
            total += weight
        }

        val target = random.nextDouble() * total
        var cumulative = 0.0
        var lastPossible = 0
        for (id in scores.indices) {
            val weight = probabilities[id]
            if (weight == 0.0) {
                continue
            }
            cumulative += weight
            lastPossible = id
            if (cumulative > target) {
                return id
            }
        }
        // Rounding left the target at the very end of the distribution
        return lastPossible
    }

    companion object {
        /** The index of the first largest score (np.argmax). */
        fun argmax(scores: FloatArray): Int {
            var best = 0
            for (id in 1 until scores.size) {
                if (scores[id] > scores[best]) {
                    best = id
                }
            }
            return best
        }
    }
}

/** The last [capacity] ids added (collections.deque(maxlen=capacity)): a repetition penalty's history. */
internal class RecentTokens(val capacity: Int) {
    // Before the array that the capacity sizes
    init {
        require(capacity >= 0) { "the window must not be negative, got $capacity" }
    }

    private val ids = IntArray(capacity)
    private var next = 0

    var size = 0
        private set

    fun add(id: Int) {
        if (capacity == 0) {
            return
        }
        ids[next] = id
        next = (next + 1) % capacity
        if (size < capacity) {
            size++
        }
    }

    /** Copies the ids (in no particular order) to the start of [destination]; returns how many. */
    fun copyInto(destination: IntArray): Int {
        ids.copyInto(destination, endIndex = size)
        return size
    }
}
