package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.prompt.SlotSource

/** The first [length] slots of [tokens] and [sources]: a decoder input, or what a cache holds. */
internal class SlotSequence(
    val tokens: IntArray,
    val sources: Array<SlotSource?>,
    val length: Int,
) {
    init {
        require(length in 0..tokens.size && length <= sources.size) {
            "length $length exceeds the slots (${tokens.size} tokens, ${sources.size} sources)"
        }
    }

    /** Positions from the start where both the token ids and the sources agree. */
    fun commonPrefix(other: SlotSequence): Int {
        val limit = minOf(length, other.length)
        for (position in 0 until limit) {
            if (tokens[position] != other.tokens[position] || sources[position] != other.sources[position]) {
                return position
            }
        }
        return limit
    }
}

/** The decoder outputs after a position: its logits and hidden state. */
internal class DecoderOutputs(val logits: FloatArray, val hidden: FloatArray)

/**
 * The recurrent (convolution) state of the decoder after [position] positions: with the KV entries
 * before [position], which later slots never overwrite, the cache can rewind to it.
 */
internal class CacheSnapshot(val position: Int, val convStates: FloatArray) {
    /**
     * The decoder outputs after the snapshot's last position. Only the latest prefill end keeps
     * them: a generation that repeats that prompt starts from them without feeding anything.
     */
    var outputs: DecoderOutputs? = null
}

/** The snapshots of a cache, by position: at most [limit], the earliest dropped first. */
internal class SnapshotStore(private val limit: Int = MAX_SNAPSHOTS) {
    private val snapshots = ArrayList<CacheSnapshot>()

    init {
        require(limit > 0) { "the limit must be positive, got $limit" }
    }

    val positions: List<Int>
        get() = snapshots.map { snapshot -> snapshot.position }

    val size: Int
        get() = snapshots.size

    /** Adds [snapshot], replacing one at its position; positions must not decrease. */
    fun add(snapshot: CacheSnapshot) {
        val last = snapshots.lastOrNull()
        require(last == null || last.position <= snapshot.position) {
            "snapshot at ${snapshot.position} after one at ${last?.position}: drop the later ones first"
        }
        if (last != null && last.position == snapshot.position) {
            snapshots.removeAt(snapshots.lastIndex)
        }
        snapshots.add(snapshot)
        if (snapshot.outputs != null) {
            for (other in snapshots) {
                if (other !== snapshot) {
                    other.outputs = null
                }
            }
        }
        while (snapshots.size > limit) {
            snapshots.removeAt(0)
        }
    }

    /** Forgets the snapshots after [position]: the cache is rewound to it. */
    fun dropAfter(position: Int) {
        snapshots.removeAll { snapshot -> snapshot.position > position }
    }

    fun clear() {
        snapshots.clear()
    }

    /**
     * The latest snapshot at or before [prefix] that a generation of [targetLength] positions can
     * start from: one before the target's end (at least one position is fed, which gives the
     * outputs), or at its end with saved outputs.
     */
    fun latestUsable(prefix: Int, targetLength: Int): CacheSnapshot? =
        snapshots.lastOrNull { snapshot ->
            val position = snapshot.position
            position <= prefix && (position < targetLength || snapshot.outputs != null)
        }

    companion object {
        /** About 24 MB of convolution states (745 KB each). */
        const val MAX_SNAPSHOTS = 32
    }
}

/** How a cache is brought to a target sequence. */
internal sealed interface CachePlan {
    /** The first position to feed: the cache keeps everything before it. */
    val feedFrom: Int

    /** The cache is a prefix of the target: keep it all and feed the rest. */
    class Append(override val feedFrom: Int) : CachePlan

    /** Rewind to [snapshot] (null: to the empty cache with zero states) and feed from there. */
    class Rewind(val snapshot: CacheSnapshot?) : CachePlan {
        override val feedFrom: Int
            get() = snapshot?.position ?: 0
    }

    companion object {
        /**
         * The plan that keeps the longest reusable part of [cached] for [target].
         *
         * The cache is kept whole when it is a prefix of the target, unless the two are equal and
         * the decoder's live outputs ([outputsValid]) no longer follow the cache's last position.
         * Otherwise it rewinds to the latest usable snapshot within the common prefix: the KV
         * entries before it stay valid and the ones after are overwritten as the rest is fed.
         */
        fun of(cached: SlotSequence, outputsValid: Boolean, snapshots: SnapshotStore, target: SlotSequence): CachePlan {
            require(target.length > 0) { "the target must not be empty" }
            val prefix = cached.commonPrefix(target)
            val cacheIsPrefix = prefix == cached.length
            if (cacheIsPrefix && (target.length > cached.length || outputsValid)) {
                return Append(cached.length)
            }
            val snapshot = snapshots.latestUsable(prefix, target.length)
            return Rewind(snapshot)
        }
    }
}

/** One decoder run of a prefill: positions [start] until [end]. */
internal data class PrefillChunk(val start: Int, val end: Int, val endsAtCheckpoint: Boolean) {
    val size: Int
        get() = end - start
}

internal object PrefillChunks {
    /** Positions per decoder run: longer runs are not faster, and need more scratch memory. */
    const val MAX_CHUNK = 256

    /**
     * The runs that feed positions [from] until [to]: at most [maxChunk] positions each, split at
     * every checkpoint in between (where the cache takes a snapshot), except that the last run never
     * feeds a single position when more are fed: a checkpoint just before the last position (the
     * prompt's end before a mode marker) is passed over, and a run cut by [maxChunk] leaves two.
     *
     * One run of the last two positions costs less than two runs, and it keeps the numerics of
     * feeding the whole prompt at once: a one-position run takes the decode kernels, whose rounding
     * differs, and greedy speech codes can flip at near-ties.
     */
    fun of(from: Int, to: Int, checkpoints: IntArray, maxChunk: Int = MAX_CHUNK): List<PrefillChunk> {
        require(from in 0..to) { "cannot feed from $from to $to" }
        require(maxChunk > 0) { "chunks must hold at least one position, got $maxChunk" }
        val chunks = ArrayList<PrefillChunk>()
        var start = from
        while (start < to) {
            var limit = minOf(start + maxChunk, to)
            if (to - limit == 1 && limit - start > 1) {
                limit--
            }
            val nextCheckpoint = checkpoints.firstOrNull { checkpoint ->
                checkpoint in (start + 1)..limit && checkpoint != to - 1
            }
            val end = nextCheckpoint ?: limit
            val endsAtCheckpoint = end in checkpoints
            chunks.add(PrefillChunk(start, end, endsAtCheckpoint))
            start = end
        }
        return chunks
    }
}
