package com.danemadsen.curie.engine.tokenizer

import java.util.PriorityQueue

/**
 * The BPE model of tokenizer.json: a pre-token's bytes start as one token each and adjacent tokens
 * merge, the lowest-ranked mergeable pair first and the leftmost among equal ranks, until no pair
 * has a merge (tokenizers' Word::merge_all). With ignore_merges a pre-token that is a vocabulary
 * token as a whole stays one token.
 *
 * Thread-safe: the per-piece cache is the only state.
 */
internal class BpeModel(
    private val vocabulary: TokenIndex,
    private val merges: MergeTable,
    private val ignoreMerges: Boolean,

    /** The token of each single byte. */
    private val byteTokenIds: IntArray,
) {
    private val cache = PieceCache()

    /** The token ids of a pre-token [piece]. The array is shared: callers must not change it. */
    fun encode(piece: String): IntArray {
        val cached = synchronized(cache) { cache[piece] }
        if (cached != null) {
            return cached
        }

        val bytes = piece.toByteArray(Charsets.UTF_8)
        val ids = encodeBytes(bytes)
        if (piece.length <= MAX_CACHED_PIECE_LENGTH) {
            synchronized(cache) { cache[piece] = ids }
        }
        return ids
    }

    private fun encodeBytes(bytes: ByteArray): IntArray {
        if (ignoreMerges) {
            val wholeToken = vocabulary.find(bytes)
            if (wholeToken != TokenIndex.NOT_FOUND) {
                return intArrayOf(wholeToken)
            }
        }

        val symbols = IntArray(bytes.size) { index ->
            val byte = bytes[index].toInt() and 0xFF
            byteTokenIds[byte]
        }
        return mergeAll(symbols)
    }

    /** Merges [ids] in place as a linked list of symbols; returns the surviving ones in order. */
    private fun mergeAll(ids: IntArray): IntArray {
        val count = ids.size
        val previous = IntArray(count) { index -> index - 1 }
        val next = IntArray(count) { index -> if (index + 1 < count) index + 1 else NONE }
        val removed = BooleanArray(count)

        val queue = PriorityQueue<PairMerge>()
        for (position in 0 until count - 1) {
            queueMerge(queue, ids[position], ids[position + 1], position)
        }

        while (queue.isNotEmpty()) {
            val merge = queue.poll()
            val position = merge.position
            val right = next[position]
            if (removed[position] || right == NONE) {
                continue
            }
            // The pair changed since this entry was queued
            if (merges.rank(ids[position], ids[right]) != merge.rank) {
                continue
            }

            ids[position] = merges.mergedId(merge.rank)
            removed[right] = true
            val afterRight = next[right]
            next[position] = afterRight
            if (afterRight != NONE) {
                previous[afterRight] = position
            }

            val before = previous[position]
            if (before != NONE) {
                queueMerge(queue, ids[before], ids[position], before)
            }
            if (afterRight != NONE) {
                queueMerge(queue, ids[position], ids[afterRight], position)
            }
        }

        val survivors = ArrayList<Int>(count)
        for (position in 0 until count) {
            if (!removed[position]) {
                survivors.add(ids[position])
            }
        }
        return survivors.toIntArray()
    }

    /** Queues the merge of [left] and [right] at [position], if they have one. */
    private fun queueMerge(queue: PriorityQueue<PairMerge>, left: Int, right: Int, position: Int) {
        val rank = merges.rank(left, right)
        if (rank != MergeTable.NO_MERGE) {
            val candidate = PairMerge(rank, position)
            queue.add(candidate)
        }
    }

    /** A candidate merge at [position]: lower ranks first, then leftmost. */
    private class PairMerge(val rank: Int, val position: Int) : Comparable<PairMerge> {
        override fun compareTo(other: PairMerge): Int {
            if (rank != other.rank) {
                return rank.compareTo(other.rank)
            }
            return position.compareTo(other.position)
        }
    }

    /** The most recently used pieces' tokens, like tokenizers' word cache. */
    private class PieceCache : LinkedHashMap<String, IntArray>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean =
            size > MAX_CACHED_PIECES
    }

    private companion object {
        const val NONE = -1
        const val MAX_CACHED_PIECES = 10_000
        const val MAX_CACHED_PIECE_LENGTH = 256
        const val INITIAL_CACHE_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
