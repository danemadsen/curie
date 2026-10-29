package com.danemadsen.curie.engine.tokenizer

/**
 * The BPE merges: for each pair of token ids its rank (the merge's position in tokenizer.json) and
 * the id of the merged token. Pairs live in open-addressed primitive arrays, about 8 MB for the
 * 293k merges where a map of boxed pairs would take over 25 MB.
 */
internal class MergeTable {
    private var keys = LongArray(INITIAL_CAPACITY) { EMPTY }
    private var ranks = IntArray(INITIAL_CAPACITY)
    private var mergedIds = IntArray(INITIAL_CAPACITY)
    private var pairCount = 0

    /** Merges added so far; the next merge gets this rank. */
    var size = 0
        private set

    /**
     * Adds the merge of [left] and [right] into [mergedId] with the next rank. A pair added again
     * takes the later rank, as in tokenizers.
     */
    fun add(left: Int, right: Int, mergedId: Int) {
        if (pairCount >= keys.size / 4 * 3) {
            grow()
        }
        if (size == mergedIds.size) {
            val capacity = maxOf(size * 2, INITIAL_CAPACITY)
            mergedIds = mergedIds.copyOf(capacity)
        }

        val key = pairKey(left, right)
        val slot = slotOf(keys, key)
        if (keys[slot] == EMPTY) {
            keys[slot] = key
            pairCount++
        }
        ranks[slot] = size
        mergedIds[size] = mergedId
        size++
    }

    /** The rank of merging [left] with [right], or [NO_MERGE]. */
    fun rank(left: Int, right: Int): Int {
        val key = pairKey(left, right)
        val slot = slotOf(keys, key)
        if (keys[slot] == EMPTY) {
            return NO_MERGE
        }
        return ranks[slot]
    }

    /** The token the merge of rank [rank] produces. */
    fun mergedId(rank: Int): Int = mergedIds[rank]

    /** Drops the spare room of the per-rank array once every merge is in. */
    fun trimToSize() {
        mergedIds = mergedIds.copyOf(size)
    }

    /** Doubles the table: it stays at most three quarters full. */
    private fun grow() {
        val oldKeys = keys
        val oldRanks = ranks
        val capacity = oldKeys.size * 2
        keys = LongArray(capacity) { EMPTY }
        ranks = IntArray(capacity)
        for (index in oldKeys.indices) {
            val key = oldKeys[index]
            if (key == EMPTY) {
                continue
            }
            val slot = slotOf(keys, key)
            keys[slot] = key
            ranks[slot] = oldRanks[index]
        }
    }

    companion object {
        const val NO_MERGE = -1

        private const val INITIAL_CAPACITY = 1024

        // Token ids are never negative, so no pair key is -1
        private const val EMPTY = -1L
        private const val GOLDEN_RATIO = -0x61c8864680b583ebL

        private fun pairKey(left: Int, right: Int): Long {
            val high = left.toLong() shl 32
            val low = right.toLong() and 0xFFFFFFFFL
            return high or low
        }

        /** The slot holding [key], or the empty slot where it belongs (linear probing). */
        private fun slotOf(keys: LongArray, key: Long): Int {
            val mask = keys.size - 1
            val mixed = key * GOLDEN_RATIO
            var slot = (mixed ushr 32).toInt() and mask
            while (keys[slot] != EMPTY && keys[slot] != key) {
                slot = (slot + 1) and mask
            }
            return slot
        }
    }
}
